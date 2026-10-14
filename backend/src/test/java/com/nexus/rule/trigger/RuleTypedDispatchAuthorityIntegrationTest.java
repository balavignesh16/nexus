package com.nexus.rule.trigger;

import com.nexus.rule.domain.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Typed sources dispatch only through a persisted, approved ActionPlan, and every current-owner and target gate still
 * runs inside the command acceptance transaction. Successful publication uses the real Mosquitto broker; it is
 * MQTT publication evidence only, not ACK, convergence or physical actuation.
 */
class RuleTypedDispatchAuthorityIntegrationTest extends TypedTriggerSupport {
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    @org.springframework.beans.factory.annotation.Autowired com.nexus.rule.persistence.RuleVersionStore versionStore;
    private int commands(UUID device) { return count("SELECT count(*) FROM action_history WHERE device_id=?", device); }

    @Test void selectedTypedCommandIsAcceptedPublishedAndRecordedThroughTheNormalLifecycle() {
        UUID device = device();
        UUID rule = typedRule(device, "typed command", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        ingest(device, 25, now.minusSeconds(5));
        assertEquals(1, commands(device));
        var history = sql.queryForMap("SELECT status,match_id,ack_status FROM action_history WHERE device_id=?", device);
        assertEquals("SUCCESS", history.get("status"), "published to the real broker");
        assertNull(history.get("ack_status"), "publication is not an ACK");
        assertEquals(1, count("SELECT count(*) FROM current_desired_twin_state WHERE device_id=?", device));
        assertEquals(1, count("SELECT count(*) FROM rule_action_candidates c JOIN rule_action_plans p USING(plan_id) WHERE c.rule_id=? AND c.disposition='SELECTED' AND c.request_id=(SELECT request_id FROM action_history WHERE device_id=?)", rule, device));
        verify(executor, times(1)).publishPrepared(any(), any());
    }

    @Test void pausingBetweenPlanPersistenceAndDispatchDeniesNewAcceptance() {
        UUID device = device();
        UUID rule = typedRule(device, "typed pause race", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        doAnswer(inv -> {
            Object planned = inv.callRealMethod();
            lifecycle.transition(rule, "pause", null);   // ownership is lost after the plan is durable
            return planned;
        }).when(planStore).persist(any(), any());
        try { ingest(device, 25, now.minusSeconds(5)); } finally { reset(planStore); }
        assertEquals(1, matches(rule));
        assertEquals(1, count("SELECT count(*) FROM rule_action_candidates WHERE rule_id=? AND disposition='SELECTED'", rule));
        assertEquals(0, commands(device), "a captured plan cannot authorize a paused rule");
        assertEquals(0, count("SELECT count(*) FROM current_desired_twin_state WHERE device_id=?", device));
        assertEquals(0, count("SELECT count(*) FROM current_twin_verification_state WHERE device_id=?", device));
        verify(executor, never()).publishPrepared(any(), any());
    }

    @Test void conflictingRulesFromOneFactAreArbitratedByPriority() {
        UUID device = device();
        UUID winner = typedRule(device, "priority 1", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        UUID loser = typedRule(device, "priority 5", 5, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, false)));
        ingest(device, 25, now.minusSeconds(5));
        assertEquals(1, commands(device), "only the arbitration winner reaches command acceptance");
        assertEquals("SELECTED", sql.queryForObject("SELECT disposition FROM rule_action_candidates WHERE rule_id=?", String.class, winner));
        var suppressed = sql.queryForMap("SELECT disposition,reason,winner_candidate_id FROM rule_action_candidates WHERE rule_id=?", loser);
        assertEquals("SUPPRESSED_PRIORITY", suppressed.get("disposition"));
        assertNotNull(suppressed.get("winner_candidate_id"));
        assertEquals(1, matches(loser), "a suppressed candidate keeps its attributed match");
        verify(executor, times(1)).publishPrepared(any(), any());
    }

    @Test void equalPriorityConflictDispatchesNothingAndRecordsConflictEvidence() {
        UUID device = device();
        typedRule(device, "a", 3, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        typedRule(device, "b", 3, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, false)));
        ingest(device, 25, now.minusSeconds(5));
        assertEquals(0, commands(device));
        assertEquals(2, count("SELECT count(*) FROM rule_action_candidates c JOIN rule_action_plans p USING(plan_id) JOIN rule_trigger_facts f ON f.fact_id=p.fact_id WHERE f.device_id=? AND c.disposition='SUPPRESSED_CONFLICT'", device));
        verify(executor, never()).publishPrepared(any(), any());
    }

    @Test void identicalCommandsFromTwoRulesCoalesceIntoOnePublication() {
        UUID device = device();
        UUID first = typedRule(device, "same 1", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        UUID second = typedRule(device, "same 2", 2, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        ingest(device, 25, now.minusSeconds(5));
        assertEquals(1, commands(device));
        assertEquals("SELECTED", sql.queryForObject("SELECT disposition FROM rule_action_candidates WHERE rule_id=?", String.class, first));
        assertEquals("COALESCED", sql.queryForObject("SELECT disposition FROM rule_action_candidates WHERE rule_id=?", String.class, second));
        // One execution with a legitimate primary-match link; the contributor is a recorded candidate pointing at the winner, not a second execution.
        UUID primaryMatch = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, first);
        assertEquals(primaryMatch, sql.queryForObject("SELECT match_id FROM action_history WHERE device_id=?", UUID.class, device));
        assertEquals(sql.queryForObject("SELECT candidate_id FROM rule_action_candidates WHERE rule_id=?", UUID.class, first),
            sql.queryForObject("SELECT winner_candidate_id FROM rule_action_candidates WHERE rule_id=?", UUID.class, second));
        assertEquals(1, count("SELECT count(*) FROM rule_matches WHERE rule_id=?", second), "the contributor keeps its own attributed match");
        verify(executor, times(1)).publishPrepared(any(), any());
    }

    @Test void archivedOrReplacedVersionCannotDispatchFromAnOldPlan() {
        UUID device = device();
        UUID rule = typedRule(device, "typed version race", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        doAnswer(inv -> {
            Object planned = inv.callRealMethod();
            var view = versionStore;
            lifecycle.legacySave(new Rule(rule, "replacement", true, 1, view.get(rule).orElseThrow().definition().conditions(),
                List.of(alert()), Map.of(), null, null, view.get(rule).orElseThrow().definition().runtime()), null);
            return planned;
        }).when(planStore).persist(any(), any());
        try { ingest(device, 25, now.minusSeconds(5)); } finally { reset(planStore); }
        assertEquals(0, commands(device), "the replaced version's captured plan no longer owns execution");
        verify(executor, never()).publishPrepared(any(), any());
    }
}
