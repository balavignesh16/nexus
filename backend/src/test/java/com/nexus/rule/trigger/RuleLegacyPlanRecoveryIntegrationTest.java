package com.nexus.rule.trigger;

import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.rule.application.RuleMatcher;
import com.nexus.rule.domain.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * F1: a legacy batch whose matches were persisted but whose ActionPlan was never created is recovered from PERSISTED evidence only,
 * bounded and idempotent, without touching cooldown/edge state and without ever repeating an acceptance. Nothing here resends or
 * republishes a command; a plan that already exists is never recovered (the M-B boundary).
 */
class RuleLegacyPlanRecoveryIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleLegacyPlanRecoveryScanner recovery;
    @Autowired RuleMatcher matcher;
    @org.springframework.boot.test.mock.mockito.SpyBean com.nexus.action.application.ActionExecutionEngine engine;

    @AfterEach void restore() { reset(planStore); }

    private UUID legacyRule(UUID device, String name, Integer cooldown, RuleAction action) {
        UUID id = UUID.randomUUID();
        lifecycle.legacySave(new Rule(id, name, true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()),
            new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"), temperatureAbove("10")), List.of(action), Map.of(), cooldown), null);
        return id;
    }
    private UUID eventOf(UUID rule) { return sql.queryForObject("SELECT event_id FROM rule_matches WHERE rule_id=? ORDER BY matched_at DESC LIMIT 1", UUID.class, rule); }
    private int commands(UUID device) { return count("SELECT count(*) FROM action_history WHERE device_id=? AND action_type='SEND_COMMAND'", device); }
    private int plans(UUID event) { return count("SELECT count(*) FROM rule_action_plans WHERE event_id=?", event); }
    private Map<String, Object> row(UUID event) { return sql.queryForMap("SELECT status,attempts,reason,last_error,next_attempt_at FROM rule_legacy_plan_recovery WHERE event_id=?", event); }
    private void planWriteFailsOnce() { doThrow(new IllegalStateException("injected plan outage")).doCallRealMethod().when(planStore).persist(any(), any()); }
    private void planWriteAlwaysFails() { doThrow(new IllegalStateException("injected plan outage")).when(planStore).persist(any(), any()); }
    private Map<String, Object> cooldown(UUID rule) {
        var rows = sql.queryForList("SELECT last_matched_at,last_match_id FROM rule_cooldown_state WHERE rule_id=? AND scope_key=''", rule);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    @Test void aMatchSavedWithoutItsPlanIsRecoveredOnceWithoutTouchingCooldownOrRepeatState() {
        UUID device = device();
        UUID rule = legacyRule(device, "recover me", 60, ledCommand(device, true));
        planWriteFailsOnce();
        ingest(device, 25, now);
        UUID event = eventOf(rule);
        assertEquals(1, count("SELECT count(*) FROM rule_matches WHERE rule_id=?", rule), "the attributed match is history");
        assertEquals(0, plans(event));
        assertEquals(0, commands(device));
        var cooldownBefore = cooldown(rule);
        assertNotNull(cooldownBefore.get("last_match_id"), "the cooldown was claimed with the match");
        int repeatBefore = count("SELECT count(*) FROM rule_repeat_state WHERE rule_id=?", rule);

        assertEquals(0, recovery.recover(10), "inside the grace period the inline flow still owns the batch");
        now = now.plusSeconds(40);
        assertEquals(1, recovery.recover(10));

        assertEquals(1, plans(event));
        assertEquals("SELECTED", sql.queryForObject("SELECT disposition FROM rule_action_candidates c JOIN rule_action_plans p ON p.plan_id=c.plan_id WHERE p.event_id=? AND c.rule_id=?", String.class, event, rule));
        assertEquals(1, commands(device), "exactly one command was accepted");
        assertEquals(1, count("SELECT count(*) FROM current_desired_twin_state WHERE device_id=?", device));
        assertEquals(1, count("SELECT count(*) FROM twin_state_transitions WHERE device_id=? AND transition_type='DESIRED_STATE_CHANGED'", device));
        verifyPublications(1);
        assertEquals("RECOVERED", row(event).get("status"));
        assertEquals("PLAN_CREATED", row(event).get("reason"));
        assertEquals(1, count("SELECT count(*) FROM rule_matches WHERE rule_id=?", rule), "no second match");
        assertEquals(cooldownBefore, cooldown(rule), "recovery consumed no additional cooldown authority");
        assertEquals(repeatBefore, count("SELECT count(*) FROM rule_repeat_state WHERE rule_id=?", rule));
        assertEquals("ACCEPTED", sql.queryForObject("SELECT outcome FROM rule_action_candidates WHERE rule_id=?", String.class, rule));

        assertEquals(0, recovery.recover(10), "RECOVERED is terminal");
        assertEquals(1, commands(device));
        verifyPublications(1);
    }

    @Test void aTransientFailureDuringRecoveryIsRecordedBackedOffAndRetriedWithinABoundedBudget() {
        UUID device = device();
        UUID rule = legacyRule(device, "transient", null, ledCommand(device, true));
        planWriteAlwaysFails();
        ingest(device, 25, now);
        UUID event = eventOf(rule);
        now = now.plusSeconds(40);
        assertEquals(0, recovery.recover(10));
        var first = row(event);
        assertEquals("FAILED", first.get("status"));
        assertEquals(1, first.get("attempts"));
        assertTrue(first.get("last_error").toString().contains("injected plan outage"), "the diagnostic is durable");
        assertNotNull(first.get("next_attempt_at"));
        assertEquals(0, recovery.recover(10), "still inside the backoff window");
        assertEquals(1, row(event).get("attempts"));

        reset(planStore);                                                        // the database recovers
        now = now.plusSeconds(20);                                               // past the 5 s backoff, still inside the 120 s age bound
        assertEquals(1, recovery.recover(10));
        assertEquals("RECOVERED", row(event).get("status"));
        assertEquals(2, row(event).get("attempts"));
        assertEquals(1, commands(device));
    }

    @Test void aPersistentFailureIsQuarantinedAfterBoundedAttemptsAndNeverRetriedAgain() {
        UUID device = device();
        UUID rule = legacyRule(device, "persistent", null, ledCommand(device, true));
        planWriteAlwaysFails();
        ingest(device, 25, now);
        UUID event = eventOf(rule);
        org.springframework.test.util.ReflectionTestUtils.setField(recovery, "maxAgeSeconds", 1_000_000L);   // isolate the attempt budget from the age bound
        try {
            now = now.plusSeconds(40);
            for (int i = 0; i < 8; i++) { recovery.recover(10); now = now.plusSeconds(400); }              // every pass is past the (capped) backoff
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(recovery, "maxAgeSeconds", 120L); }
        var finalRow = row(event);
        assertEquals("QUARANTINED", finalRow.get("status"), finalRow.toString());
        assertEquals(5, finalRow.get("attempts"), "bounded attempts");
        assertTrue(finalRow.get("last_error").toString().contains("injected plan outage"));
        reset(planStore);
        now = now.plusSeconds(3600);
        assertEquals(0, recovery.recover(10), "a terminal row is never retried");
        assertEquals(0, plans(event));
        assertEquals(0, commands(device));
    }

    @Test void recoveryRechecksOwnershipSoAPausedReplacedOrResumedRuleNeverActsOnTheOldMatch() {
        for (String change : List.of("pause", "replace", "resume")) {
            UUID device = device();
            UUID rule = legacyRule(device, "owner " + change, null, ledCommand(device, true));
            planWriteFailsOnce();
            ingest(device, 25, now);
            UUID event = eventOf(rule);
            switch (change) {
                case "pause" -> lifecycle.transition(rule, "pause", null);
                case "replace" -> {
                    var view = context().get(rule).orElseThrow();
                    lifecycle.legacySave(new Rule(rule, "replacement", true, 1, view.definition().conditions(), List.of(alert()), Map.of(), null), null);
                }
                default -> { lifecycle.transition(rule, "pause", null); lifecycle.transition(rule, "resume", null); }
            }
            now = now.plusSeconds(40);
            resetPublicationSpiesKeepingStubs();
            assertEquals(0, recovery.recover(10), change);
            assertEquals("EXPIRED", row(event).get("status"), change);
            assertEquals("OWNERSHIP_CHANGED", row(event).get("reason"), change);
            assertEquals(0, plans(event), change + ": no plan, no candidates");
            assertEquals(0, commands(device), change);
            verifyNoPublication();
            reset(planStore);
            sql.update("UPDATE rules SET lifecycle='PAUSED', enabled=false WHERE rule_id=?", rule);
        }
    }

    @Test void aBatchTooOldToActOnSafelyIsExpiredAndNeverDispatched() {
        UUID device = device();
        UUID rule = legacyRule(device, "too old", null, ledCommand(device, true));
        planWriteFailsOnce();
        ingest(device, 25, now);
        UUID event = eventOf(rule);
        now = now.plusSeconds(600);
        assertEquals(0, recovery.recover(10));
        assertEquals("EXPIRED", row(event).get("status"));
        assertEquals("AGE_EXCEEDED", row(event).get("reason"));
        assertEquals(0, plans(event));
        assertEquals(0, commands(device));
    }

    @Test void whatCannotBeReconstructedTruthfullyIsRecordedUnrecoverableNotInventedFromCurrentState() {
        UUID device = device();
        UUID missingEvent = legacyRule(device, "event gone", null, ledCommand(device, true));
        planWriteFailsOnce();
        ingest(device, 25, now);
        UUID event = eventOf(missingEvent);
        sql.update("DELETE FROM domain_events WHERE event_id=?", event);
        now = now.plusSeconds(40);
        recovery.recover(10);
        assertEquals("UNRECOVERABLE", row(event).get("status"));
        assertEquals("SOURCE_EVENT_MISSING", row(event).get("reason"));
        assertEquals(0, plans(event));
        assertEquals(0, commands(device));

        reset(planStore);
        UUID device2 = device();
        UUID unattributed = legacyRule(device2, "unattributed", null, ledCommand(device2, true));
        planWriteFailsOnce();
        ingest(device2, 25, now);
        UUID event2 = eventOf(unattributed);
        sql.update("UPDATE rule_matches SET rule_version_id=NULL, rule_revision=NULL, execution_epoch=NULL WHERE rule_id=?", unattributed);
        now = now.plusSeconds(40);
        recovery.recover(10);
        assertEquals("UNRECOVERABLE", row(event2).get("status"));
        assertEquals("UNATTRIBUTED_MATCH", row(event2).get("reason"));
        assertEquals(0, plans(event2));
        assertEquals(0, commands(device2));
    }

    @Test void aDuplicateDeliveryOfTheSameEventReusesItsMatchAndCreatesAtMostOnePlanAndCommand() {
        UUID device = device();
        UUID rule = legacyRule(device, "duplicate delivery", null, ledCommand(device, true));
        planWriteFailsOnce();
        ingest(device, 25, now);
        UUID event = eventOf(rule);
        assertEquals(0, plans(event));
        var duplicate = new DomainEvent(event, EventType.TWIN_UPDATED, device, now, "test", Map.of(), "INFO", sql.queryForObject("SELECT correlation_id FROM domain_events WHERE event_id=?", String.class, event));
        matcher.onEvent(duplicate);                                              // redelivery of the very same event
        assertEquals(1, count("SELECT count(*) FROM rule_matches WHERE rule_id=?", rule), "the persisted match was reused, not duplicated");
        assertEquals(1, plans(event), "and the redelivery created the missing plan");
        assertEquals(1, commands(device));
        matcher.onEvent(duplicate);
        matcher.onEvent(duplicate);
        assertEquals(1, count("SELECT count(*) FROM rule_matches WHERE rule_id=?", rule));
        assertEquals(1, plans(event));
        assertEquals(1, commands(device), "redelivery never accepts a second command");
        now = now.plusSeconds(40);
        assertEquals(0, recovery.recover(10), "nothing is left for the scanner");
        verifyPublications(1);
    }

    @Test void twoConcurrentRecoveryWorkersProduceOnePlanAndOneCommand() throws Exception {
        UUID device = device();
        UUID rule = legacyRule(device, "concurrent recovery", null, ledCommand(device, true));
        planWriteFailsOnce();
        ingest(device, 25, now);
        UUID event = eventOf(rule);
        now = now.plusSeconds(40);
        var pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) results.add(pool.submit(() -> { start.await(); return recovery.recover(10); }));
        start.countDown();
        int created = 0;
        for (var f : results) created += f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, created);
        assertEquals(1, plans(event));
        assertEquals(1, commands(device));
        verifyPublications(1);
        assertEquals("RECOVERED", row(event).get("status"));
    }

    @Test void anExistingPlanThatWasNeverDispatchedIsNeverRecoveredBecauseThereIsNoResend() {
        UUID device = device();
        UUID rule = legacyRule(device, "plan without acceptance", null, ledCommand(device, true));
        // Persist the plan for real but crash before dispatch: the batch's plan exists and nothing was accepted.
        doThrow(new IllegalStateException("simulated crash before dispatch")).when(engine).process(any(), any(), any());
        try { ingest(device, 25, now); } finally { reset(engine); }
        UUID event = eventOf(rule);
        assertEquals(1, plans(event), "the plan row committed");
        assertEquals(0, commands(device));
        now = now.plusSeconds(40);
        assertEquals(0, recovery.recover(10));
        assertEquals(0, count("SELECT count(*) FROM rule_legacy_plan_recovery WHERE event_id=? AND status NOT IN ('HISTORICAL','RECOVERED')", event));
        assertEquals(1, plans(event), "no second plan");
        assertEquals(0, commands(device), "an approved plan is a decision: it is never turned into a command by recovery");
        verifyNoPublication();
    }

    @Test void historicalMatchesFromBeforeRecoveryExistedAreNeverReplayed() {
        UUID device = device();
        UUID rule = legacyRule(device, "historical", null, ledCommand(device, true));
        planWriteFailsOnce();
        ingest(device, 25, now);
        UUID event = eventOf(rule);
        sql.update("INSERT INTO rule_legacy_plan_recovery(event_id,status,reason,discovered_at,updated_at) VALUES(?,'HISTORICAL','PRE_RECOVERY_MATCH',now(),now())", event);
        now = now.plusSeconds(40);
        assertEquals(0, recovery.recover(10));
        assertEquals(0, plans(event));
        assertEquals(0, commands(device));
        assertEquals("HISTORICAL", row(event).get("status"));
    }

    private com.nexus.rule.persistence.RuleVersionStore context() { return versions; }
    @Autowired com.nexus.rule.persistence.RuleVersionStore versions;
}
