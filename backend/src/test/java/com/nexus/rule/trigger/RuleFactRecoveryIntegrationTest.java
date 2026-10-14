package com.nexus.rule.trigger;

import com.nexus.rule.application.RuleEvaluationSnapshotService;
import com.nexus.rule.domain.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Routing durability: a committed fact is never silently lost. Failures are recorded (FAILED, bounded attempts, QUARANTINED),
 * the recovery scanner re-routes idempotently (unique rule/version/fact decisions, one plan per fact), a match whose plan was never
 * persisted gets its single plan, and ownership is rechecked before any action. Nothing here resends or republishes a command.
 */
class RuleFactRecoveryIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleFactRecoveryScanner recovery;
    @Autowired RuleEvaluationSnapshotService snapshots;
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));
    private final AtomicBoolean outage = new AtomicBoolean();

    @AfterEach void restore() { snapshots.setReadProbe(null); reset(planStore); }

    private void databaseOutage(boolean on) {
        outage.set(on);
        snapshots.setReadProbe(on ? phase -> { if (outage.get()) throw new IllegalStateException("injected snapshot outage"); } : null);
    }
    private UUID onlyFact(UUID device) { return sql.queryForObject("SELECT fact_id FROM rule_trigger_facts WHERE trigger_type='ACCEPTED_TELEMETRY' AND device_id=?", UUID.class, device); }
    private String status(UUID fact) { return sql.queryForObject("SELECT routing_status FROM rule_trigger_facts WHERE fact_id=?", String.class, fact); }

    @Test void aRoutingFailureIsRecordedAndRecoveredExactlyOnce() {
        UUID device = device();
        UUID rule = typedRule(device, "recover", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()));
        clearInvocations(alerts);
        databaseOutage(true);
        ingest(device, 25, now.minusSeconds(5));                  // the source operation succeeds; routing cannot
        databaseOutage(false);
        UUID fact = onlyFact(device);
        assertEquals("FAILED", status(fact));
        assertNotNull(sql.queryForObject("SELECT routing_error FROM rule_trigger_facts WHERE fact_id=?", String.class, fact));
        assertEquals(0, matches(rule));
        assertEquals(0, recovery.recover(10), "still inside its backoff window");
        now = now.plusSeconds(40);          // past the 30 s grace and the 5 s backoff, still inside the 120 s freshness threshold
        assertEquals(1, recovery.recover(10));
        assertEquals("ROUTED", status(fact));
        assertEquals(1, matches(rule));
        assertEquals(1, count("SELECT count(*) FROM rule_action_candidates WHERE rule_id=? AND disposition='SELECTED'", rule));
        verify(alerts, times(1)).execute(any(), any());
        assertEquals(0, recovery.recover(10));
        assertEquals(1, matches(rule));
        verify(alerts, times(1)).execute(any(), any());
    }

    @Test void aMatchWhosePlanWasNeverPersistedGetsItsSinglePlanWithoutADuplicateMatchOrCommand() {
        UUID device = device();
        UUID rule = typedRule(device, "plan recovery", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        doThrow(new IllegalStateException("injected plan outage")).doCallRealMethod().when(planStore).persist(any(), any());
        ingest(device, 25, now.minusSeconds(5));
        UUID fact = onlyFact(device);
        assertEquals(1, matches(rule), "the attributed match is history");
        assertEquals(0, count("SELECT count(*) FROM rule_action_candidates WHERE rule_id=?", rule));
        assertEquals(0, count("SELECT count(*) FROM action_history WHERE device_id=?", device), "no plan, no command");
        assertEquals("FAILED", status(fact));
        now = now.plusSeconds(40);
        assertEquals(1, recovery.recover(10));
        assertEquals(1, matches(rule), "the persisted decision was reused");
        assertEquals(1, count("SELECT count(*) FROM rule_action_candidates WHERE rule_id=? AND disposition='SELECTED'", rule));
        assertEquals(1, count("SELECT count(*) FROM action_history WHERE device_id=?", device), "exactly one command");
        verify(executor, times(1)).publishPrepared(any(), any());
        assertEquals(0, recovery.recover(10));
        verify(executor, times(1)).publishPrepared(any(), any());
    }

    /**
     * Declared boundary M-B: an approved plan is a decision, not proof of dispatch. When the process dies after the plan commits and
     * before the command is accepted, the plan stays without acceptance and recovery must NOT dispatch it (no resend, no outbox in
     * Phase 3). The crash is emulated by failing the dispatch of the persisted plan and then returning the fact to the state a crash
     * would have left (PENDING, because routed() never ran).
     */
    @Test void aPlanPersistedBeforeACrashStaysWithoutAcceptanceAndRecoveryNeverResendsIt() {
        UUID device = device();
        UUID rule = typedRule(device, "crash after plan", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        doThrow(new IllegalStateException("simulated crash before dispatch")).when(engine).process(any(), any(), any());
        ingest(device, 25, now.minusSeconds(5));
        reset(engine);
        UUID fact = onlyFact(device);
        assertEquals(1, count("SELECT count(*) FROM rule_action_plans WHERE fact_id=? AND status='APPROVED'", fact), "the decision is durable");
        assertEquals(1, count("SELECT count(*) FROM rule_action_candidates WHERE rule_id=? AND disposition='SELECTED'", rule));
        assertEquals(0, count("SELECT count(*) FROM action_history WHERE device_id=?", device), "but no command was accepted");
        sql.update("UPDATE rule_trigger_facts SET routing_status='PENDING', routed_at=NULL WHERE fact_id=?", fact);   // what a crash before routed() leaves
        now = now.plusSeconds(40);
        assertEquals(1, recovery.recover(10));
        assertEquals("ROUTED", status(fact));
        assertEquals(1, count("SELECT count(*) FROM rule_action_plans WHERE fact_id=?", fact), "the plan was reused, not recreated");
        assertEquals(1, matches(rule));
        assertEquals(0, count("SELECT count(*) FROM action_history WHERE device_id=?", device), "recovery never redispatches an existing plan");
        verify(executor, never()).publishPrepared(any(), any());
    }

    @Test void concurrentRecoveryWorkersProduceOneDecisionAndOneCommand() throws Exception {
        UUID device = device();
        UUID rule = typedRule(device, "concurrent recovery", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        databaseOutage(true);
        ingest(device, 25, now.minusSeconds(5));
        databaseOutage(false);
        now = now.plusSeconds(40);
        var pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) results.add(pool.submit(() -> { start.await(); return recovery.recover(10); }));
        start.countDown();
        int routed = 0;
        for (var r : results) routed += r.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, routed, "the lease lets exactly one worker route the fact");
        assertEquals(1, matches(rule));
        assertEquals(1, count("SELECT count(*) FROM action_history WHERE device_id=?", device));
        verify(executor, times(1)).publishPrepared(any(), any());
    }

    @Test void aPermanentlyFailingFactIsQuarantinedAfterBoundedAttemptsAndNeverRetried() {
        UUID device = device();
        UUID rule = typedRule(device, "quarantine", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()));
        databaseOutage(true);
        ingest(device, 25, now.minusSeconds(5));
        UUID fact = onlyFact(device);
        for (int i = 0; i < 8; i++) { now = now.plusSeconds(900); recovery.recover(10); }
        databaseOutage(false);
        assertEquals("QUARANTINED", status(fact));
        assertEquals(5, sql.queryForObject("SELECT routing_attempts FROM rule_trigger_facts WHERE fact_id=?", Integer.class, fact));
        now = now.plusSeconds(900);
        assertEquals(0, recovery.recover(10), "quarantine is terminal for the scanner");
        assertEquals(0, matches(rule));
        assertEquals(5, sql.queryForObject("SELECT routing_attempts FROM rule_trigger_facts WHERE fact_id=?", Integer.class, fact));
    }

    @Test void recoveryRechecksOwnershipSoAPausedRuleNeverActsOnARecoveredFact() {
        UUID device = device();
        UUID rule = typedRule(device, "paused before recovery", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        doThrow(new IllegalStateException("injected plan outage")).doCallRealMethod().when(planStore).persist(any(), any());
        ingest(device, 25, now.minusSeconds(5));
        assertEquals(1, matches(rule));
        lifecycle.transition(rule, "pause", null);                // ownership is lost between the fact and its recovery
        now = now.plusSeconds(900);
        recovery.recover(10);
        assertEquals(0, count("SELECT count(*) FROM rule_action_candidates WHERE rule_id=?", rule), "a paused rule gets no plan");
        assertEquals(0, count("SELECT count(*) FROM action_history WHERE device_id=?", device));
        verify(executor, never()).publishPrepared(any(), any());
    }

    @Test void recoveredFactsOfAReplacedVersionCannotUseTheOldVersion() {
        UUID device = device();
        UUID rule = typedRule(device, "version replaced before recovery", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        databaseOutage(true);
        ingest(device, 25, now.minusSeconds(5));
        databaseOutage(false);
        var view = versionStore.get(rule).orElseThrow();
        lifecycle.legacySave(new Rule(rule, "replacement", true, 1, view.definition().conditions(), List.of(alert()), Map.of(), null, null, view.definition().runtime()), null);
        now = now.plusSeconds(900);
        recovery.recover(10);
        verify(executor, never()).publishPrepared(any(), any());
        assertEquals(0, count("SELECT count(*) FROM action_history WHERE device_id=?", device), "the old version's command is never accepted");
    }

    @Autowired com.nexus.rule.persistence.RuleVersionStore versionStore;
    @org.springframework.boot.test.mock.mockito.SpyBean com.nexus.action.application.ActionExecutionEngine engine;
}
