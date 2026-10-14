package com.nexus.rule.trigger;

import com.nexus.action.application.ActionExecutionEngine;
import com.nexus.action.application.ActionExecutorRegistry;
import com.nexus.action.application.CommandAcceptanceService;
import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.action.application.executor.WebhookExecutor;
import com.nexus.action.domain.ActionType;
import com.nexus.rule.domain.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * F4: a candidate's recorded outcome reflects what the executor evidence PROVES. An ActionHistory row alone never means success:
 * a failed webhook, an unsupported executor and a command rejected before acceptance are recorded as such, publication and ACK stay
 * separate facts (read from ActionHistory, never inferred), and with no row nothing is claimed.
 */
@AutoConfigureMockMvc
class RuleExecutionOutcomeIntegrationTest extends TypedTriggerSupport {
    @SpyBean WebhookExecutor webhook;
    @SpyBean ActionExecutorRegistry registry;
    @SpyBean CommandAcceptanceService acceptance;
    @SpyBean ActionExecutionEngine engine;
    @Autowired MockMvc mvc;
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    @AfterEach void restore() { reset(webhook); reset(registry); reset(acceptance); reset(engine); }

    private UUID rule(RuleAction action) {
        UUID id = UUID.randomUUID();
        lifecycle.legacySave(new Rule(id, "outcome", true, 1, List.of(temperatureAbove("10")), List.of(action), Map.of(), null, null, new RuleRuntime(TELEMETRY, null, null)), null);
        return id;
    }
    private static RuleAction webhookAction() { return new RuleAction("WEBHOOK", Map.of("url", "http://example.invalid/hook")); }
    private Map<String, Object> candidate(UUID rule) {
        return sql.queryForMap("SELECT outcome,outcome_reason,request_id,disposition FROM rule_action_candidates WHERE rule_id=?", rule);
    }
    private Map<String, Object> history(Object requestId) {
        return sql.queryForMap("SELECT status,message,published_at,ack_status FROM action_history WHERE request_id=?", requestId);
    }

    @Test void aCommandThatPassedGuardedAcceptanceIsAcceptedAndPublicationAndAckStayOutOfIt() throws Exception {
        UUID trigger = device(), target = device();
        UUID rule = rule(ledCommand(target, true));
        ingest(trigger, 25, now);
        var c = candidate(rule);
        assertEquals("ACCEPTED", c.get("outcome"));
        assertNull(c.get("outcome_reason"));
        var h = history(c.get("request_id"));
        assertEquals("SUCCESS", h.get("status"), "P2.4: SUCCESS is publication to the disposable broker");
        assertNotNull(h.get("published_at"));
        assertNull(h.get("ack_status"), "no device ACK exists, and none is invented");
        UUID matchId = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, rule);
        mvc.perform(get("/api/v1/rule-matches/history/" + matchId + "/arbitration")).andExpect(status().isOk())
            .andExpect(jsonPath("$.plans[0].candidates[0].outcome").value("ACCEPTED"))
            .andExpect(jsonPath("$.plans[0].candidates[0].history_status").value("SUCCESS"))
            .andExpect(jsonPath("$.plans[0].candidates[0].published_at").isNotEmpty())
            .andExpect(jsonPath("$.plans[0].candidates[0].ack_status").isEmpty());
    }

    @Test void aFailedWebhookIsExecutionFailedAndNeverAccepted() throws Exception {
        UUID trigger = device();
        doThrow(new IllegalStateException("webhook endpoint refused the call")).when(webhook).execute(any(), any());
        UUID rule = rule(webhookAction());
        ingest(trigger, 25, now);
        var c = candidate(rule);
        assertEquals("SELECTED", c.get("disposition"), "the decision itself stands");
        assertEquals("EXECUTION_FAILED", c.get("outcome"));
        assertTrue(c.get("outcome_reason").toString().contains("webhook endpoint refused"), c.get("outcome_reason").toString());
        assertEquals("FAILURE", history(c.get("request_id")).get("status"), "an ActionHistory row exists, and it says FAILURE");
        UUID matchId = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, rule);
        mvc.perform(get("/api/v1/rule-matches/history/" + matchId + "/arbitration")).andExpect(status().isOk())
            .andExpect(jsonPath("$.plans[0].candidates[0].outcome").value("EXECUTION_FAILED"))
            .andExpect(jsonPath("$.plans[0].candidates[0].history_status").value("FAILURE"));
    }

    @Test void aSuccessfulStubWebhookIsExecutedNotAccepted() {
        UUID trigger = device();
        UUID rule = rule(webhookAction());
        ingest(trigger, 25, now);
        var c = candidate(rule);
        assertEquals("EXECUTED", c.get("outcome"), "a stub executor ran; that proves nothing outside the system");
        assertEquals("SUCCESS", history(c.get("request_id")).get("status"));
    }

    @Test void anUnsupportedExecutorIsRecordedAsUnsupported() {
        UUID trigger = device();
        doReturn(Optional.empty()).when(registry).resolve(ActionType.WEBHOOK);
        UUID rule = rule(webhookAction());
        ingest(trigger, 25, now);
        var c = candidate(rule);
        assertEquals("UNSUPPORTED", c.get("outcome"));
        assertTrue(c.get("outcome_reason").toString().contains("No executor found"), c.get("outcome_reason").toString());
        assertEquals("UNSUPPORTED", history(c.get("request_id")).get("status"));
    }

    @Test void aCommandRejectedBeforeAcceptanceIsExecutionFailedWithNoDesiredStateAndNoPublication() {
        UUID trigger = device(), target = device();
        doThrow(new CommandExecutor.CommandRejectedException("device refused the command")).when(acceptance).accept(any(), any(), any(), any());
        UUID rule = rule(ledCommand(target, true));
        ingest(trigger, 25, now);
        var c = candidate(rule);
        assertEquals("EXECUTION_FAILED", c.get("outcome"), "a FAILURE row without a durable acceptance is a rejection, never ACCEPTED");
        assertTrue(c.get("outcome_reason").toString().contains("device refused"), c.get("outcome_reason").toString());
        assertEquals("FAILURE", history(c.get("request_id")).get("status"));
        assertEquals(0, count("SELECT count(*) FROM twin_state_transitions WHERE transition_type='DESIRED_STATE_CHANGED' AND command_id=?", c.get("request_id")));
        assertEquals(0, count("SELECT count(*) FROM current_desired_twin_state WHERE device_id=?", target));
        verify(executor, never()).publishPrepared(any(), any());
    }

    @Test void withNoActionHistoryRowNothingIsClaimed() {
        UUID trigger = device(), target = device();
        doNothing().when(engine).process(any(), any(), any());
        UUID rule = rule(ledCommand(target, true));
        ingest(trigger, 25, now);
        var c = candidate(rule);
        assertEquals("SELECTED", c.get("disposition"));
        assertNull(c.get("outcome"), "unknown, not accepted");
        assertEquals(0, count("SELECT count(*) FROM action_history WHERE request_id=?", c.get("request_id")));
    }

    @Test void aGuardedDenialIsDeniedAndWritesNoHistory() {
        UUID trigger = device(), target = device();
        UUID rule = rule(ledCommand(target, true));
        // Pause between the persisted plan and acceptance.
        doAnswer(inv -> { Object planned = inv.callRealMethod(); lifecycle.transition(rule, "pause", null); return planned; }).when(planStore).persist(any(), any());
        try { ingest(trigger, 25, now); } finally { reset(planStore); }
        var c = candidate(rule);
        assertEquals("DENIED", c.get("outcome"));
        assertEquals(0, count("SELECT count(*) FROM action_history WHERE request_id=?", c.get("request_id")));
    }
}
