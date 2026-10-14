package com.nexus.rule.temporal;

import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.rule.domain.*;
import com.nexus.rule.timer.RuleTimerService;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** FOR_DURATION: qualified continuous truth with freshness coverage, deadline equality, resets, ownership and concurrent workers. */
class RuleForDurationIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleTimerService timers;
    @Autowired com.nexus.rule.persistence.RuleVersionStore versionStore;

    private UUID durationRule(UUID device, long seconds) {
        return temporalRule(device, "for duration", new TemporalSpec(TemporalOperator.FOR_DURATION, seconds, null, null, null, null), true, RuleOperator.GT, "10", List.of(alert()));
    }

    @Test void firesOnlyAfterContinuousQualifiedCoverageAndCompletesAtEquality() {
        UUID device = device();
        UUID rule = durationRule(device, 60);
        Instant t0 = now;
        ingest(device, 25, t0);
        assertEquals("ARMED", phase(rule), sql.queryForList("SELECT event,details::text FROM rule_temporal_evidence WHERE rule_id=?", rule)+""+sql.queryForList("SELECT phase,reset_reason,watermark::text FROM rule_temporal_state WHERE rule_id=?", rule));
        assertEquals(1, pendingTimers(rule));
        now = t0.plusSeconds(30);
        ingest(device, 26, now);
        assertEquals(1, pendingTimers(rule), "extending coverage keeps one deadline");
        clearInvocations(alerts);
        now = t0.plusSeconds(60).minusMillis(1);
        assertEquals(0, timers.processDue(10), "not due before the deadline");
        assertEquals(0, dueFacts(rule));
        now = t0.plusSeconds(60);                                // now >= dueAt completes
        assertEquals(1, timers.processDue(10));
        assertEquals(1, dueFacts(rule));
        assertEquals(1, matches(rule));
        assertEquals("SATISFIED", phase(rule));
        verify(alerts, times(1)).execute(any(), any());
        String evidence = sql.queryForObject("SELECT evaluation_evidence::text FROM rule_matches WHERE rule_id=?", String.class, rule);
        assertTrue(evidence.contains("SATISFIED") && evidence.contains("FOR_DURATION") && evidence.contains("TEMPORAL_DUE"), evidence);
        assertEquals(1, temporalEvents(rule, "SATISFIED"));
        assertEquals(0, count("SELECT count(*) FROM rule_matches WHERE rule_id=? AND event_id IS NOT NULL", rule), "no fabricated DomainEvent");
        assertEquals("SUCCESS", sql.queryForObject("SELECT status FROM action_history WHERE match_id=(SELECT match_id FROM rule_matches WHERE rule_id=?)", String.class, rule));
    }

    @Test void aFalseObservationResetsPendingProgress() {
        UUID device = device();
        UUID rule = durationRule(device, 60);
        Instant t0 = now;
        ingest(device, 25, t0);
        now = t0.plusSeconds(10);
        ingest(device, 5, now);                                  // predicate false
        assertEquals("IDLE", phase(rule));
        assertEquals(0, pendingTimers(rule));
        assertEquals(1, temporalEvents(rule, "RESET"));
        now = t0.plusSeconds(61);
        assertEquals(0, timers.processDue(10));
        assertEquals(0, matches(rule));
    }

    @Test void nonGoodQualityResetsProgressWithoutManufacturingAFalseObservation() {
        UUID device = device();
        UUID rule = durationRule(device, 60);
        Instant t0 = now;
        ingest(device, 25, t0);
        now = t0.plusSeconds(10);
        connectivity.applyObservedStatus(device, DeviceConnectivityStatus.OFFLINE);   // OFFLINE is non-GOOD evidence
        assertEquals("IDLE", phase(rule));
        assertEquals(0, pendingTimers(rule));
        String reason = sql.queryForObject("SELECT reset_reason FROM rule_temporal_state WHERE rule_id=?", String.class, rule);
        assertTrue(reason.startsWith("QUALITY_OR_UNIT_"), reason);
        now = t0.plusSeconds(120);
        assertEquals(0, timers.processDue(10));
        assertEquals(0, matches(rule));
    }

    @Test void aFreshnessGapPreventsASingleStaleSampleFromProvingContinuousTruth() {
        UUID device = device();
        UUID rule = durationRule(device, 300);
        Instant t0 = now;
        ingest(device, 25, t0);                                  // covers until t0 + 120 s
        now = t0.plusSeconds(250);                               // gap of 130 s > freshness threshold
        ingest(device, 26, now);
        assertEquals(1, temporalEvents(rule, "RESET"), "the gap is detected when evidence resumes");
        assertEquals("ARMED", phase(rule), "a new window starts from the new qualified sample");
        Instant due = sql.queryForObject("SELECT due_at FROM rule_timers WHERE rule_id=? AND status='PENDING'", java.sql.Timestamp.class, rule).toInstant();
        assertTrue(java.time.Duration.between(t0.plusSeconds(550), due).abs().compareTo(java.time.Duration.ofNanos(1000)) <= 0, "deadline = new window start + 300 s (microsecond storage precision)");
        now = t0.plusSeconds(300);
        assertEquals(0, timers.processDue(10), "the original window no longer exists");
        now = due;
        assertEquals(1, timers.processDue(10));
        assertEquals(0, dueFacts(rule), "the last sample's coverage ended before the new deadline");
        assertEquals(2, temporalEvents(rule, "RESET"), "gap at resume and gap at due are both evidence");
        String reason = sql.queryForObject("SELECT reset_reason FROM rule_temporal_state WHERE rule_id=?", String.class, rule);
        assertEquals("FRESHNESS_GAP_AT_DUE", reason);
    }

    @Test void pauseResumeAndVersionSwitchInvalidateOldGenerationsSoNoOldProgressSurvives() {
        UUID device = device();
        UUID rule = durationRule(device, 60);
        Instant t0 = now;
        ingest(device, 25, t0);
        assertEquals(1, pendingTimers(rule));
        lifecycle.transition(rule, "pause", null);
        assertEquals(0, pendingTimers(rule), "pause invalidates the pending generation");
        lifecycle.transition(rule, "resume", null);
        assertEquals(0, pendingTimers(rule), "resume does not carry pending progress");
        assertEquals(2, count("SELECT count(DISTINCT generation) FROM rule_temporal_state WHERE rule_id=?", rule));
        now = t0.plusSeconds(60);
        assertEquals(0, timers.processDue(10));
        assertEquals(0, matches(rule), "an old-generation deadline cannot dispatch after ABA pause/resume");
        // New progress after resume works normally and belongs to the new generation only.
        now = t0.plusSeconds(70);
        ingest(device, 27, now);
        assertEquals(1, pendingTimers(rule));
        // A replacing version also drops the pending window.
        var definition = versionStore.get(rule).orElseThrow().definition();
        lifecycle.legacySave(new Rule(rule, "replacement", true, 1, definition.conditions(), definition.actions(), Map.of(), null, null, definition.runtime()), null);
        assertEquals(0, pendingTimers(rule));
        now = t0.plusSeconds(200);
        assertEquals(0, timers.processDue(10));
        assertEquals(0, matches(rule));
    }

    @Test void twoConcurrentWorkersProduceExactlyOneDecisionForOneOccurrence() throws Exception {
        UUID device = device();
        UUID rule = durationRule(device, 60);
        Instant t0 = now;
        ingest(device, 25, t0);
        clearInvocations(alerts);
        now = t0.plusSeconds(60);
        var pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int worker = 0; worker < 4; worker++) results.add(pool.submit(() -> { start.await(); return timers.processDue(10); }));
        start.countDown();
        int handled = 0;
        for (var result : results) handled += result.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, handled, "SKIP LOCKED plus idempotent consumption: one worker owns the occurrence");
        assertEquals(1, dueFacts(rule));
        assertEquals(1, matches(rule));
        verify(alerts, times(1)).execute(any(), any());
    }

    @Test void aLateWorkerRechecksEvidenceAndDoesNotCompleteOnStaleReadings() {
        UUID device = device();
        UUID rule = durationRule(device, 60);
        Instant t0 = now;
        ingest(device, 25, t0);
        now = t0.plusSeconds(600);                               // the worker was down; the only sample is stale now
        assertEquals(1, timers.processDue(10));
        assertEquals(0, dueFacts(rule));
        assertEquals("NOT_QUALIFIED_AT_DUE", sql.queryForObject("SELECT reset_reason FROM rule_temporal_state WHERE rule_id=?", String.class, rule));
        assertEquals(0, matches(rule));
    }
}
