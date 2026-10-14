package com.nexus.rule.temporal;

import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.rule.domain.*;
import com.nexus.rule.timer.RuleTimerService;
import com.nexus.rule.trigger.RuleFactRecoveryScanner;
import com.nexus.rule.trigger.TriggerObservation;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * H1: the post-commit race. A fact's routing is held back at the temporal observation step with a latch while later facts are
 * committed and applied, which reproduces "intermediate observation applied late". Progress must never survive a false,
 * non-GOOD or unknowable observation, and completion must wait for evidence that is committed but not yet applied.
 */
class RuleTemporalObservationIntegrityIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleTimerService timers;
    @Autowired RuleFactRecoveryScanner recovery;
    @Autowired com.nexus.rule.trigger.RuleTriggerRouter routerBean;
    @SpyBean RuleTemporalRuntime temporal;

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach void release() { reset(temporal); pool.shutdownNow(); }

    private UUID durationRule(UUID device, long seconds) {
        return temporalRule(device, "duration", new TemporalSpec(TemporalOperator.FOR_DURATION, seconds, null, null, null, null), true, RuleOperator.GT, "10", List.of(alert()));
    }

    private static String sampleValue(TriggerObservation fact) {
        Object sample = fact.sourceRef().get("sample");
        return sample instanceof Map<?, ?> m ? String.valueOf(m.get("value")) : null;
    }

    /** Holds the temporal observation of every fact matching the predicate until released. */
    private CountDownLatch holdObservation(java.util.function.Predicate<TriggerObservation> which, CountDownLatch entered) {
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            TriggerObservation fact = invocation.getArgument(0);
            if (which.test(fact)) { entered.countDown(); assertTrue(release.await(60, TimeUnit.SECONDS)); }
            return invocation.callRealMethod();
        }).when(temporal).observe(any());
        return release;
    }

    @Test void trueFalseTrueWithTheFalseObservationAppliedLastNeverCompletes() throws Exception {
        UUID device = device();
        UUID rule = durationRule(device, 60);
        Instant t0 = now;
        ingest(device, 25, t0);                                               // F1 true: window armed, due t0+60
        var entered = new CountDownLatch(1);
        var release = holdObservation(f -> f.type() == TriggerType.ACCEPTED_TELEMETRY && sampleValue(f) != null && new BigDecimal(sampleValue(f)).compareTo(new BigDecimal("10")) < 0, entered);
        now = t0.plusSeconds(10);
        Future<?> falseFact = pool.submit(() -> ingest(device, 5, t0.plusSeconds(10)));   // F2 false: committed, routing held
        assertTrue(entered.await(30, TimeUnit.SECONDS));
        now = t0.plusSeconds(20);
        ingest(device, 26, t0.plusSeconds(20));                               // F3 true: committed and applied while F2 is unapplied
        assertEquals("ARMED", phase(rule), "the true observation extends the window - exactly the state the old code could complete from");

        now = t0.plusSeconds(60);
        timers.processDue(10);
        assertEquals(0, dueFacts(rule), "completion waits for committed evidence that is not applied yet");
        assertEquals(0, matches(rule));
        assertEquals(1, pendingTimers(rule), "the deadline is deferred, not consumed");
        assertTrue(temporalEvents(rule, "COMPLETION_DEFERRED") >= 1);

        release.countDown();
        falseFact.get(30, TimeUnit.SECONDS);                                  // F2 is applied now, late
        assertEquals("IDLE", phase(rule));
        assertEquals(0, pendingTimers(rule), "the false observation reset the window it belonged to");
        assertEquals(0, temporalEvents(rule, "SUPERSEDED"), "no observation is skipped; each is judged by its own recorded sample");
        String reason = sql.queryForObject("SELECT details->>'reason' FROM rule_temporal_evidence WHERE rule_id=? AND event='RESET' ORDER BY seq DESC LIMIT 1", String.class, rule);
        assertEquals("PREDICATE_FALSE", reason, "judged on F2's own value (5), not the current Twin value (26)");
        now = t0.plusSeconds(61);
        timers.processDue(10);
        assertEquals(0, matches(rule));
        assertEquals(0, dueFacts(rule));
    }

    @Test void goodNonGoodGoodWithTheOfflineObservationAppliedLastNeverCompletes() throws Exception {
        UUID device = device();
        UUID rule = durationRule(device, 60);
        Instant t0 = now;
        ingest(device, 25, t0);
        var entered = new CountDownLatch(1);
        var release = holdObservation(f -> f.type() == TriggerType.CONNECTIVITY_CHANGED && "OFFLINE".equals(String.valueOf(f.attributes().get("connectivity"))), entered);
        now = t0.plusSeconds(10);
        Future<?> offline = pool.submit(() -> connectivity.applyObservedStatus(device, DeviceConnectivityStatus.OFFLINE));
        assertTrue(entered.await(30, TimeUnit.SECONDS));
        now = t0.plusSeconds(15);
        connectivity.applyObservedStatus(device, DeviceConnectivityStatus.ONLINE);   // back ONLINE before the OFFLINE fact was applied
        now = t0.plusSeconds(20);
        ingest(device, 26, t0.plusSeconds(20));                                      // GOOD again
        assertEquals("ARMED", phase(rule));
        now = t0.plusSeconds(60);
        timers.processDue(10);
        assertEquals(0, dueFacts(rule), "the OFFLINE interval is not applied yet, so the window cannot be proven continuous");
        release.countDown();
        offline.get(30, TimeUnit.SECONDS);
        assertEquals("IDLE", phase(rule));
        assertTrue(sql.queryForObject("SELECT reset_reason FROM rule_temporal_state WHERE rule_id=?", String.class, rule).startsWith("QUALITY_OR_UNIT_"));
        now = t0.plusSeconds(61);
        timers.processDue(10);
        assertEquals(0, matches(rule));
    }

    @Test void aTrendWithAnInterveningDipAppliedLateIsNeverSatisfied() throws Exception {
        UUID device = device();
        UUID rule = temporalRule(device, "trend", new TemporalSpec(TemporalOperator.TREND, 40L, 300L, null, TemporalSpec.Direction.RISING, new BigDecimal("5")), true, RuleOperator.GT, "0", List.of(alert()));
        Instant t0 = now;
        ingest(device, 20, t0);
        now = t0.plusSeconds(20); ingest(device, 22, now);
        var entered = new CountDownLatch(1);
        var release = holdObservation(f -> f.type() == TriggerType.ACCEPTED_TELEMETRY && sampleValue(f) != null && new BigDecimal(sampleValue(f)).compareTo(new BigDecimal("21")) == 0, entered);
        now = t0.plusSeconds(30);
        Future<?> dip = pool.submit(() -> ingest(device, 21, t0.plusSeconds(30)));        // the dip (22 -> 21), routing held
        assertTrue(entered.await(30, TimeUnit.SECONDS));
        now = t0.plusSeconds(45);
        ingest(device, 30, t0.plusSeconds(45));                                          // 20, 22, 30 would look monotonic without the dip
        assertEquals(0, matches(rule), "completion is deferred while an earlier observation in the window is unapplied");
        assertTrue(temporalEvents(rule, "COMPLETION_DEFERRED") >= 1);
        release.countDown();
        dip.get(30, TimeUnit.SECONDS);
        assertEquals(0, matches(rule), "the late dip invalidates the series instead of being skipped");
        assertEquals(0, temporalEvents(rule, "SUPERSEDED"));
        assertEquals("IDLE", phase(rule));
        assertEquals("OUT_OF_ORDER_SAMPLE", sql.queryForObject("SELECT reset_reason FROM rule_temporal_state WHERE rule_id=?", String.class, rule));
    }

    @Test void anObservationThatCannotBeReconstructedFailsClosed() {
        UUID device = device();
        UUID rule = durationRule(device, 300);
        Instant t0 = now;
        ingest(device, 25, t0);
        assertEquals("ARMED", phase(rule));
        // A committed accepted-telemetry fact without its recorded sample (for example written by an older build).
        UUID fact = UUID.randomUUID();
        sql.update("INSERT INTO rule_trigger_facts(fact_id,trigger_type,source_key,device_id,occurred_at,recorded_at,source_ref,attributes) VALUES(?,'ACCEPTED_TELEMETRY','legacy-shape',?,now(),now(),'{}'::jsonb,'{\"capabilityKey\":\"temperature\"}'::jsonb)", fact, device);
        routerBean.route(fact);
        assertEquals("IDLE", phase(rule));
        assertEquals("OBSERVATION_NOT_RECONSTRUCTABLE", sql.queryForObject("SELECT reset_reason FROM rule_temporal_state WHERE rule_id=?", String.class, rule));
        assertEquals(0, pendingTimers(rule), "no continuity is claimed through an unknowable observation");
    }

    @Test void aSwallowedObservationFailureIsRetriedThenQuarantinedAndResetsContinuity() {
        UUID device = device();
        UUID rule = durationRule(device, 60);
        Instant t0 = now;
        ingest(device, 25, t0);
        doAnswer(invocation -> {
            TriggerObservation fact = invocation.getArgument(0);
            if (fact.type() == TriggerType.ACCEPTED_TELEMETRY && sampleValue(fact) != null && new BigDecimal(sampleValue(fact)).compareTo(new BigDecimal("10")) < 0)
                throw new IllegalStateException("injected observation failure");
            return invocation.callRealMethod();
        }).when(temporal).observe(any());
        now = t0.plusSeconds(10);
        ingest(device, 5, now);                                                  // the false observation cannot be applied
        UUID failing = sql.queryForObject("SELECT fact_id FROM rule_trigger_facts WHERE trigger_type='ACCEPTED_TELEMETRY' AND device_id=? ORDER BY recorded_at DESC LIMIT 1", UUID.class, device);
        assertEquals("FAILED", sql.queryForObject("SELECT routing_status FROM rule_trigger_facts WHERE fact_id=?", String.class, failing), "the failure is recorded, not swallowed");
        assertEquals(1, sql.queryForObject("SELECT routing_attempts FROM rule_trigger_facts WHERE fact_id=?", Integer.class, failing));
        now = t0.plusSeconds(20);
        ingest(device, 26, now);
        now = t0.plusSeconds(60);
        timers.processDue(10);
        assertEquals(0, dueFacts(rule), "no completion while the committed false observation is unapplied");
        for (int attempt = 0; attempt < 6; attempt++) { now = now.plusSeconds(900); recovery.recover(10); }
        assertEquals("QUARANTINED", sql.queryForObject("SELECT routing_status FROM rule_trigger_facts WHERE fact_id=?", String.class, failing));
        assertEquals(5, sql.queryForObject("SELECT routing_attempts FROM rule_trigger_facts WHERE fact_id=?", Integer.class, failing), "bounded retries");
        assertEquals("OBSERVATION_UNAVAILABLE", sql.queryForObject("SELECT reset_reason FROM rule_temporal_state WHERE rule_id=?", String.class, rule),
            "quarantine invalidates continuity that depended on the lost observation");
        assertEquals(0, recovery.recover(10), "a quarantined fact is never retried");
        now = now.plusSeconds(900);
        timers.processDue(10);
        assertEquals(0, matches(rule));
    }
}
