package com.nexus.rule.ai;

import com.nexus.rule.domain.*;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scenario 67 (integration half): on disposable PostgreSQL and Mosquitto, the explanation of a REAL match is built from the PERSISTED version and
 * arbitration rows. It never re-evaluates today's rule or Twin state, never invents a selection, and keeps publication, acknowledgement and
 * actuation apart. (A command here is genuinely published to the disposable broker; nothing acknowledges it and nothing is actuated.)
 */
class RuleExplanationPersistedFactsIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleMatchExplanationService explanations;
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    private UUID rule(String name, int priority, UUID device, RuleAction action) {
        return typedRule(device, name, priority, TELEMETRY, List.of(temperatureAbove("10")), List.of(action));
    }
    private UUID matchOf(UUID rule) { return sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, rule); }
    private static List<String> texts(RuleMatchExplanation e) { return e.claims().stream().map(c -> c.tier() + ":" + c.text()).toList(); }
    private static boolean has(RuleMatchExplanation e, ClaimTier tier, String part) { return e.claims().stream().anyMatch(c -> c.tier() == tier && c.text().contains(part)); }

    @Test void theExplanationDescribesWhatArbitrationAndTheExecutorsActuallyRecordedAndNothingMore() {
        UUID trigger = device(), target = device(), other = device();
        UUID command = rule("explain command", 1, trigger, ledCommand(target, true));
        UUID loserA = rule("explain conflict a", 2, trigger, ledCommand(other, true));
        UUID loserB = rule("explain conflict b", 2, trigger, ledCommand(other, false));
        UUID alert = rule("explain alert", 3, trigger, alert());
        ingest(trigger, 30, now);
        awaitDelivery();          // with the outbox ON the command is published by the dispatcher after the acceptance
        long revision = sql.queryForObject("SELECT rule_revision FROM rule_matches WHERE rule_id=?", Long.class, command);

        var selected = explanations.explain(matchOf(command));
        assertTrue(has(selected, ClaimTier.FACT, "The rule was evaluated at revision " + revision));
        assertTrue(has(selected, ClaimTier.FACT, "Action SEND_COMMAND #0 was SELECTED by arbitration (IDENTICAL_COMMAND_COALESCED)"), texts(selected).toString());
        assertTrue(has(selected, ClaimTier.FACT, "The match was evaluated for a ACCEPTED_TELEMETRY trigger"), texts(selected).toString());
        assertTrue(has(selected, ClaimTier.FACT, "The observation used had quality GOOD"), texts(selected).toString());
        assertTrue(has(selected, ClaimTier.FACT, "recorded a desired-state change"));
        assertTrue(has(selected, ClaimTier.FACT, "published to the MQTT broker"), "the command really was published to the disposable broker: " + texts(selected));
        assertTrue(has(selected, ClaimTier.UNKNOWN, DeterministicExplanationRenderer.NO_ACK));
        // The persisted P2.4 verification record of THIS command (not inferred from publication): whatever acceptance recorded, and never "converged" here.
        String persistedVerification = sql.queryForObject("""
            SELECT t.new_verification_status FROM twin_state_transitions t JOIN rule_action_candidates k ON k.request_id=t.verification_command_id
            WHERE k.rule_id=? AND t.new_verification_status IS NOT NULL ORDER BY t.transitioned_at DESC, t.transition_id DESC LIMIT 1""", String.class, command);
        assertNotNull(persistedVerification, "acceptance persisted a verification status for the command");
        assertNotEquals("CONVERGED", persistedVerification);
        assertTrue(has(selected, ClaimTier.FACT, "verification status " + persistedVerification), texts(selected).toString());
        assertFalse(has(selected, ClaimTier.FACT, "CONVERGED"));
        assertTrue(has(selected, ClaimTier.UNKNOWN, DeterministicExplanationRenderer.PHYSICAL_UNKNOWN));
        assertFalse(selected.actionNote().toLowerCase().contains("stub"), "a rule whose only action is a command is not described by the stub sentence");
        assertTrue(selected.actionNote().contains("published to the MQTT broker"));

        for (UUID loser : List.of(loserA, loserB)) {
            var suppressed = explanations.explain(matchOf(loser));
            assertTrue(has(suppressed, ClaimTier.FACT, "was SUPPRESSED_CONFLICT by arbitration (EQUAL_PRIORITY_CONFLICT)"), texts(suppressed).toString());
            assertFalse(has(suppressed, ClaimTier.FACT, "published"), "a suppressed command was never published");
            assertFalse(has(suppressed, ClaimTier.FACT, "desired-state change"));
            assertNull(suppressed.actionNote(), "a suppressed action never ran, so no executor is described");
            assertEquals(0, count("SELECT count(*) FROM action_history h JOIN rule_action_candidates k ON k.request_id=h.request_id WHERE k.rule_id=?", loser));
        }

        var stub = explanations.explain(matchOf(alert));
        assertTrue(has(stub, ClaimTier.FACT, "The executor reported success"));
        assertTrue(stub.actionNote().contains("stub implementations") && stub.actionNote().contains("establishes no external effect"));
        assertFalse(stub.actionNote().contains("SEND_COMMAND:"));
        assertFalse(has(stub, ClaimTier.FACT, "published"));
    }

    @Test void changingTodaysRuleAndTwinStateNeverChangesTheExplanationOfAnEarlierMatch() {
        UUID trigger = device(), target = device();
        UUID rule = rule("explain stable", 1, trigger, ledCommand(target, true));
        ingest(trigger, 30, now);
        awaitDelivery();          // with the outbox ON the command is published by the dispatcher after the acceptance
        UUID match = matchOf(rule);
        var before = explanations.explain(match);

        // Today the rule is paused, redefined, and the Twin and connectivity say the opposite.
        lifecycle.transition(rule, "pause", null);
        sql.update("UPDATE digital_twins SET latest_value=0, numeric_value=0 WHERE device_id=?", trigger);
        sql.update("UPDATE device_connectivity SET status='OFFLINE' WHERE device_id=?", trigger);
        sql.update("DELETE FROM rule_cooldown_state WHERE rule_id=?", rule);

        var after = explanations.explain(match);
        assertEquals(texts(before), texts(after), "the explanation is a function of persisted history only");
        assertEquals(before.actionNote(), after.actionNote());
        assertTrue(has(after, ClaimTier.FACT, "Observed value was 30"), texts(after).toString());
    }

    @Test void aMatchWithNoPersistedArbitrationOrVersionIsExplainedAsUnknownNotInvented() {
        UUID trigger = device(), target = device();
        UUID rule = rule("explain legacy", 1, trigger, ledCommand(target, true));
        UUID historical = UUID.randomUUID();
        // A pre-P3.5-style history row: no pinned version, no plan, no candidate - plus an ActionHistory row that, on its own, proves nothing about selection.
        sql.update("INSERT INTO rule_matches(match_id,rule_id,rule_snapshot,event_id,evaluation_evidence,matched_at) VALUES(?,?,'{\"name\":\"explain legacy\"}'::jsonb,?,'{\"outcome\":\"matched\"}'::jsonb,now())",
            historical, rule, UUID.randomUUID());
        sql.update("INSERT INTO action_history(request_id,match_id,action_type,status,timestamp,message) VALUES(?,?,'SEND_COMMAND','SUCCESS',now(),'legacy dispatch')", UUID.randomUUID(), historical);
        var explanation = explanations.explain(historical);
        assertTrue(has(explanation, ClaimTier.UNKNOWN, DeterministicExplanationRenderer.NO_ARBITRATION));
        assertTrue(has(explanation, ClaimTier.UNKNOWN, DeterministicExplanationRenderer.VERSION_UNKNOWN));
        assertFalse(texts(explanation).stream().anyMatch(t -> t.contains("was SELECTED")), "no selection may be inferred from an ActionHistory row");
        assertTrue(has(explanation, ClaimTier.FACT, "NEXUS recorded a SEND_COMMAND action with status SUCCESS"), "the recorded status is reported as exactly that");
    }
}
