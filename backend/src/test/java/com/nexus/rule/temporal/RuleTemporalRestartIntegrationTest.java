package com.nexus.rule.temporal;

import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.rule.domain.*;
import com.nexus.rule.timer.RuleTimerService;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Restart recovery: deadlines and runtime generations live only in PostgreSQL. A fresh application context (not an
 * OS-process restart) reconstitutes every pending timer, then evaluates before and after the stored deadlines.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RuleTemporalRestartIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleTimerService timers;
    @Autowired com.nexus.rule.persistence.RuleRegistry registry;

    @Override protected boolean isolatePendingTimers() { return false; }

    static Instant t0;
    static UUID duration, absence, offline, schedule, interval;
    static Object firstRegistry;

    @Test @Order(1) @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void armEveryDurableKindThenRecreateTheContext() {
        t0 = now;
        UUID d1 = device(), d2 = device(), d3 = device(), d4 = device();
        duration = temporalRule(d1, "restart duration", new TemporalSpec(TemporalOperator.FOR_DURATION, 60L, null, null, null, null), true, RuleOperator.GT, "10", List.of(alert()));
        ingest(d1, 25, t0);
        absence = temporalRule(d2, "restart absence", new TemporalSpec(TemporalOperator.ABSENCE_FOR, 60L, null, null, null, null), true, RuleOperator.EQ, "x", List.of(alert()));
        offline = temporalRule(d3, "restart offline", new TemporalSpec(TemporalOperator.OFFLINE_FOR, 60L, null, null, null, null), false, RuleOperator.EQ, "OFFLINE", List.of(alert()));
        connectivity.applyObservedStatus(d3, DeviceConnectivityStatus.OFFLINE);
        schedule = typedRule(d4, "restart schedule", 1, List.of(new TriggerSubscription(TriggerType.SCHEDULE)), List.of(), List.of(alert()), null,
            new ScheduleSpec(ScheduleSpec.Kind.ONE_SHOT, t0.plusSeconds(60), null, null));
        UUID d5 = device();
        interval = typedRule(d5, "restart interval", 1, List.of(new TriggerSubscription(TriggerType.SCHEDULE)), List.of(), List.of(alert()), null,
            new ScheduleSpec(ScheduleSpec.Kind.FIXED_INTERVAL, null, 60L, t0));
        for (UUID rule : List.of(duration, absence, offline, schedule, interval)) assertEquals(1, pendingTimers(rule));
        firstRegistry = registry;
    }

    @Test @Order(2)
    void aFreshContextRecoversPendingDeadlinesAndRechecksEvidenceBeforeAndAfterThem() {
        assertNotSame(firstRegistry, registry, "an actual new Spring context");
        for (UUID rule : List.of(duration, absence, offline, schedule, interval)) assertEquals(1, pendingTimers(rule), "pending deadline survived the restart");
        now = t0.plusSeconds(59);
        assertEquals(0, timers.processDue(20) > 0 ? matches(duration) + matches(absence) + matches(offline) + matches(schedule) : 0);
        for (UUID rule : List.of(duration, absence, offline, schedule)) assertEquals(0, matches(rule), "before any deadline nothing fires");
        assertEquals(0, matches(interval), "the first interval slot is t0+60 s");

        now = t0.plusSeconds(600);                                  // the application was down well past every deadline
        timers.processDue(20);
        assertEquals(1, matches(absence), "silence persisted across the restart");
        assertEquals(1, matches(offline), "persisted OFFLINE connectivity still holds");
        assertEquals(1, matches(schedule), "an unconsumed one-shot evaluates once after restart");
        assertEquals(0, matches(duration), "stale readings cannot prove continuous truth after the outage");
        assertEquals("NOT_QUALIFIED_AT_DUE", sql.queryForObject("SELECT reset_reason FROM rule_temporal_state WHERE rule_id=?", String.class, duration));
        for (UUID rule : List.of(duration, absence, offline, schedule)) assertEquals(0, pendingTimers(rule));
        assertEquals(1, matches(interval), "slots t0+60..t0+600: the first is evaluated once and the nine later ones are recorded as skipped");
        assertEquals(9L, sql.queryForObject("SELECT (attributes->>'skippedSlots')::bigint FROM rule_trigger_facts WHERE trigger_type='SCHEDULE' AND attributes->>'ruleId'=?", Long.class, interval.toString()));
        assertEquals(1, pendingTimers(interval), "the next slot is in the future");
        assertTrue(sql.queryForObject("SELECT due_at FROM rule_timers WHERE rule_id=? AND status='PENDING'", java.sql.Timestamp.class, interval).toInstant().isAfter(now));
        timers.processDue(20);
        for (UUID rule : List.of(absence, offline, schedule)) assertEquals(1, matches(rule), "each occurrence is consumed exactly once");
    }
}
