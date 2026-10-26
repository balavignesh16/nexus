package com.nexus.action.lifecycle;

import com.nexus.rule.ai.ClaimTier;
import com.nexus.rule.ai.RuleMatchExplanation;
import com.nexus.rule.ai.RuleMatchExplanationService;
import com.nexus.rule.domain.TriggerSubscription;
import com.nexus.rule.domain.TriggerType;
import com.nexus.rule.dryrun.DryRunRequest;
import com.nexus.rule.dryrun.DryRunService;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ledger P4-113 (and the PostgreSQL half of P4-114): with the outbox ON and a command that really went through it, repeated dry-runs and
 * explanations change no row of the delivery tables, the command history, the ACK facts or the dedupe table; and an explanation of a command
 * that was only accepted or that ended as a dead letter claims neither publication nor an acknowledgement.
 */
@SpringBootTest(properties = {"nexus.command.outbox.enabled=true", "nexus.command.outbox.dispatcher-enabled=true"})
class DryRunAndExplanationZeroEffectIntegrationTest extends TypedTriggerSupport {
    @Autowired DryRunService dryRun;
    @Autowired RuleMatchExplanationService explanations;
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    private Map<String, String> fingerprint() {
        Map<String, String> out = new TreeMap<>();
        for (String table : List.of("command_outbox", "command_state_history", "inbound_message_dedupe", "action_history", "rule_matches",
                "rule_action_candidates", "current_twin_verification_state", "current_desired_twin_state"))
            out.put(table, sql.queryForObject("SELECT count(*)::text || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '') FROM " + table + " t", String.class));
        return out;
    }

    private void awaitTerminal() throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (sql.queryForObject("SELECT count(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) != 0) {
            if (System.nanoTime() > end) throw new AssertionError("outbox did not settle");
            Thread.sleep(50);
        }
    }

    private DryRunRequest current(UUID device, UUID rule) {
        return new DryRunRequest("CURRENT_STATE", new DryRunRequest.Scope(device), List.of(new DryRunRequest.VersionSelector(rule, null, null, null)),
                new DryRunRequest.TriggerSpec("ACCEPTED_TELEMETRY", device, null, null, null), null, null);
    }

    @Test
    void repeatedDryRunsAndExplanationsChangeNoDeliveryRowAndNoOutboxCommandIsCreatedByThem() throws Exception {
        UUID trigger = device(), target = device();
        UUID rule = typedRule(trigger, "zero effect", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(target, true)));
        ingest(trigger, 30, now);
        awaitTerminal();
        UUID match = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, rule);
        assertEquals(1, (int) sql.queryForObject("SELECT count(*) FROM command_outbox", Integer.class), "the real command went through the outbox");

        Map<String, String> before = fingerprint();
        for (int i = 0; i < 5; i++) {
            dryRun.run(current(trigger, rule));
            RuleMatchExplanation explanation = explanations.explain(match);
            assertNotNull(explanation);
        }
        assertEquals(before, fingerprint(), "dry-runs and explanations left every delivery, history, ACK-fact and dedupe row unchanged");
        assertEquals(1, (int) sql.queryForObject("SELECT count(*) FROM command_outbox", Integer.class), "and created no command");
    }

    @Test
    void anExplanationOfAPublishedButUnacknowledgedCommandStatesNoAcknowledgementAndNoPhysicalEffect() throws Exception {
        UUID trigger = device(), target = device();
        UUID rule = typedRule(trigger, "explain published", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(target, true)));
        ingest(trigger, 30, now);
        awaitTerminal();
        UUID match = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, rule);
        RuleMatchExplanation explanation = explanations.explain(match);
        List<String> texts = explanation.claims().stream().map(c -> c.tier() + ":" + c.text()).toList();
        assertTrue(texts.stream().anyMatch(t -> t.startsWith(ClaimTier.FACT + ":") && t.contains("published to the MQTT broker")), texts.toString());
        assertTrue(texts.stream().anyMatch(t -> t.startsWith(ClaimTier.UNKNOWN + ":") && t.contains("No device acknowledgement is recorded")), texts.toString());
        assertTrue(texts.stream().anyMatch(t -> t.startsWith(ClaimTier.UNKNOWN + ":") && t.contains("physically acted")), texts.toString());
        assertFalse(texts.stream().anyMatch(t -> t.contains("A device acknowledgement with status")), texts.toString());
    }

    @Test
    void anExplanationOfACommandThatEndedAsADeadLetterOrWasOnlyAcceptedNeverClaimsPublication() throws Exception {
        UUID trigger = device(), target = device();
        UUID rule = typedRule(trigger, "explain dead letter", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(target, true)));
        ingest(trigger, 30, now);
        awaitTerminal();
        UUID match = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, rule);
        UUID command = sql.queryForObject("SELECT request_id FROM rule_action_candidates WHERE match_id=?", UUID.class, match);

        // Put the persisted facts into the two states a real command can be in before publication, then explain: the explanation reads only what is
        // persisted (it never asks the broker), so the persisted rows decide. (Test data setup; the dispatcher is idle: nothing is unresolved.)
        sql.update("UPDATE action_history SET status='ACCEPTED_FOR_DISPATCH', published_at=NULL, ack_deadline_at=NULL WHERE request_id=?", command);
        for (String state : List.of("accepted only", "dead letter")) {
            if (state.equals("dead letter"))
                sql.update("UPDATE action_history SET status='FAILURE', message='DELIVERY_UNCONFIRMED: the dispatcher lost its lease' WHERE request_id=?", command);
            RuleMatchExplanation explanation = explanations.explain(match);
            List<String> texts = explanation.claims().stream().map(c -> c.tier() + ":" + c.text()).toList();
            assertFalse(texts.stream().anyMatch(t -> t.startsWith(ClaimTier.FACT + ":") && t.contains("published to the MQTT broker (action history status SUCCESS)")),
                    state + ": no publication claim in " + texts);
            assertTrue(texts.stream().anyMatch(t -> t.contains("publication is not established")), state + ": " + texts);
            assertTrue(texts.stream().anyMatch(t -> t.startsWith(ClaimTier.UNKNOWN + ":") && t.contains("No device acknowledgement is recorded")), state + ": " + texts);
            assertFalse(texts.stream().anyMatch(t -> t.contains("A device acknowledgement with status")), state + ": " + texts);
            assertFalse(texts.stream().anyMatch(t -> t.contains("CONVERGED")), state + ": " + texts);
        }
    }
}
