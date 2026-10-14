package com.nexus.rule.temporal;

import com.nexus.rule.application.RuleEvaluationSnapshotService;
import com.nexus.rule.domain.*;
import com.nexus.rule.timer.RuleTimerService;
import com.nexus.rule.trigger.RuleTriggerRouter;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Duplicate identities, late evidence, timer/telemetry races in both orders, and event-less explanations. */
class RuleTemporalIntegrityIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleTimerService timers;
    @Autowired RuleTriggerRouter routerBean;
    @Autowired RuleEvaluationSnapshotService snapshots;
    @Autowired com.nexus.rule.ai.RuleMatchExplanationContextBuilder explanations;

    private UUID absence(UUID device, long seconds) {
        return temporalRule(device, "absence", new TemporalSpec(TemporalOperator.ABSENCE_FOR, seconds, null, null, null, null), true, RuleOperator.EQ, "x", List.of(alert()));
    }
    private UUID duration(UUID device, long seconds) {
        return temporalRule(device, "duration", new TemporalSpec(TemporalOperator.FOR_DURATION, seconds, null, null, null, null), true, RuleOperator.GT, "10", List.of(alert()));
    }
    private Instant pendingDue(UUID rule) {
        return sql.queryForObject("SELECT due_at FROM rule_timers WHERE rule_id=? AND status='PENDING'", java.sql.Timestamp.class, rule).toInstant();
    }

    @Test void aSourceIdentityNeverAdvancesOrExtendsTemporalStateTwice() {
        UUID device = device();
        UUID forDuration = duration(device, 300);
        UUID absent = absence(device, 600);
        now = now.plusSeconds(1);                                   // strictly after the activation arm point
        ingest(device, 25, now);
        UUID fact = sql.queryForObject("SELECT fact_id FROM rule_trigger_facts WHERE trigger_type='ACCEPTED_TELEMETRY' AND device_id=?", UUID.class, device);
        Instant dueBefore = pendingDue(forDuration), absenceDueBefore = pendingDue(absent);
        routerBean.route(fact);
        routerBean.route(fact);
        assertEquals(1, temporalEvents(forDuration, "ARMED"));
        assertEquals(0, temporalEvents(forDuration, "EXTENDED"), "a repeated delivery is not a second sample");
        assertEquals(1, temporalEvents(absent, "REARMED"));
        assertEquals(dueBefore, pendingDue(forDuration));
        assertEquals(absenceDueBefore, pendingDue(absent));
        assertEquals(1, pendingTimers(forDuration));
        assertEquals(1, pendingTimers(absent));
    }

    @Test void lateEvidenceIsDiagnosedAndNeverRewindsTheWindow() {
        UUID device = device();
        UUID rule = duration(device, 300);
        Instant t0 = now;
        ingest(device, 25, t0);
        Instant due = pendingDue(rule);
        Instant armedAt = sql.queryForObject("SELECT armed_at FROM rule_temporal_state WHERE rule_id=?", java.sql.Timestamp.class, rule).toInstant();
        now = t0.minusSeconds(10);                                  // the backend receipt clock reads earlier than the stored watermark
        ingest(device, 26, t0.plusSeconds(1));
        assertEquals(1, temporalEvents(rule, "IGNORED_LATE"));
        assertEquals(due, pendingDue(rule), "the deadline did not move");
        assertEquals(armedAt, sql.queryForObject("SELECT armed_at FROM rule_temporal_state WHERE rule_id=?", java.sql.Timestamp.class, rule).toInstant());
        assertEquals("ARMED", phase(rule));
    }

    @Test void telemetryCommittedBeforeTheDeadlineRearmsAndNoDecisionIsMade() {
        UUID device = device();
        Instant t0 = now;
        UUID rule = absence(device, 60);
        now = t0.plusSeconds(59);
        ingest(device, 25, now);                                    // observation wins the race
        now = t0.plusSeconds(60);
        timers.processDue(10);
        assertEquals(0, dueFacts(rule));
        assertEquals(0, matches(rule));
        assertEquals(1, pendingTimers(rule));
        assertTrue(java.time.Duration.between(t0.plusSeconds(119), pendingDue(rule)).abs().compareTo(java.time.Duration.ofNanos(1000)) <= 0);
    }

    @Test void deadlineClaimedFirstDecidesOnItsSnapshotAndTheConcurrentObservationRearmsAfterwards() throws Exception {
        UUID device = device();
        Instant t0 = now;
        UUID rule = absence(device, 60);
        now = t0.plusSeconds(60);
        var pool = Executors.newSingleThreadExecutor();
        var started = new AtomicBoolean();
        var telemetryThread = new Future<?>[1];
        // Once the due worker has fixed its snapshot, a telemetry observation commits concurrently and routes into the same state.
        snapshots.setReadProbe(phase -> {
            if (phase.equals("VERSIONS") && started.compareAndSet(false, true)) telemetryThread[0] = pool.submit(() -> ingest(device, 25, now));
        });
        try { timers.processDue(10); } finally { snapshots.setReadProbe(null); }
        assertNotNull(telemetryThread[0], "the race was actually exercised");
        telemetryThread[0].get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, dueFacts(rule), "exactly one decision for the original occurrence");
        assertEquals(1, matches(rule));
        assertEquals("ARMED", phase(rule), "the later observation rearmed the next silence window");
        assertEquals(1, pendingTimers(rule), "no duplicated or stale pending deadline");
        assertTrue(pendingDue(rule).isAfter(t0.plusSeconds(60)));
        assertEquals(1, temporalEvents(rule, "SATISFIED"));
        assertEquals(1, temporalEvents(rule, "REARMED"));
    }

    @Test void aTypedMatchExplanationUsesPersistedFactsAndNeverInventsADomainEvent() {
        UUID device = device();
        UUID rule = absence(device, 30);
        now = now.plusSeconds(30);
        timers.processDue(10);
        UUID matchId = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, rule);
        var context = explanations.build(matchId);
        assertNull(context.eventId());
        assertNull(context.domainEvent());
        assertEquals(device, context.deviceId(), "the trigger device comes from the frozen-context evidence");
        assertTrue(context.evaluationEvidence().containsKey("frozenContext"));
        assertEquals(1, context.actions().size());
    }
}
