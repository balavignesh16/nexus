package com.nexus.rule.trigger;

import com.nexus.action.application.ActionExecutionEngine;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionType;
import com.nexus.rule.application.RuleExecutionAuthorization;
import com.nexus.rule.application.RuleExecutionSuppressedException;
import com.nexus.rule.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * M2: a valid SELECTED request id is not enough. The dispatched action type, target and parameters must equal what the persisted
 * plan holds for that candidate. Forgeries are denied with no command acceptance, desired/history/verification write or MQTT publication.
 */
class RuleTypedAuthorizationBindingIntegrationTest extends TypedTriggerSupport {
    @SpyBean ActionExecutionEngine engine;
    @Autowired RuleExecutionAuthorization authorization;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.nexus.rule.persistence.RuleVersionStore versionStore;

    record Planned(UUID device, UUID rule, UUID matchId, UUID requestId, Rule pinned) {}

    /** Produces a persisted SELECTED candidate for a LED command while the real dispatch is suppressed. */
    private Planned plannedLedCommand() {
        UUID device = device();
        UUID rule = typedRule(device, "bound command", 1, List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY)), List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        doNothing().when(engine).process(any(), any(), any());
        try { ingest(device, 25, now.minusSeconds(5)); } finally { reset(engine); }
        UUID matchId = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, rule);
        UUID requestId = sql.queryForObject("SELECT request_id FROM rule_action_candidates WHERE match_id=?", UUID.class, matchId);
        var owner = sql.queryForMap("SELECT rule_version_id,rule_revision,execution_epoch FROM rule_matches WHERE match_id=?", matchId);
        var definition = versionStore.version(rule, (UUID) owner.get("rule_version_id"));
        Rule pinned = new Rule(rule, definition.name(), true, 1, definition.conditions(), definition.actions(), Map.of(), null,
            new RuleExecutionIdentity((UUID) owner.get("rule_version_id"), ((Number) owner.get("rule_revision")).longValue(), ((Number) owner.get("execution_epoch")).longValue()), definition.runtime());
        assertEquals(0, count("SELECT count(*) FROM action_history WHERE device_id=?", device), "the plan exists but nothing was dispatched");
        return new Planned(device, rule, matchId, requestId, pinned);
    }

    private void assertNoEffects(Planned p, UUID... devices) {
        for (UUID d : devices) {
            assertEquals(0, count("SELECT count(*) FROM action_history WHERE device_id=?", d));
            assertEquals(0, count("SELECT count(*) FROM current_desired_twin_state WHERE device_id=?", d));
            assertEquals(0, count("SELECT count(*) FROM current_twin_verification_state WHERE device_id=?", d));
            assertEquals(0, count("SELECT count(*) FROM twin_state_transitions WHERE device_id=? AND transition_type IN ('DESIRED_STATE_CHANGED','CONVERGENCE_CHANGED')", d));
        }
        verify(executor, never()).publishPrepared(any(), any());
    }

    private void denied(Planned p, ActionExecutionRequest forged, String expected) {
        var thrown = assertThrows(RuleExecutionSuppressedException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(
            tx -> authorization.check(forged, new ActionContext(null, p.pinned()))));
        assertEquals(expected, thrown.getMessage());
        // And through the real engine: the suppression happens inside command acceptance, before anything is written.
        engine.process(List.of(forged), new ActionContext(null, p.pinned()), "forged");
    }

    @Test void aReusedRequestIdCannotCarryAnotherValueTargetTypeOrParameter() {
        Planned p = plannedLedCommand();
        UUID other = device();
        Map<String, Object> real = ledCommand(p.device(), true).parameters();

        var otherValue = new HashMap<>(real); otherValue.put("value", false);
        denied(p, new ActionExecutionRequest(p.requestId(), p.matchId(), ActionType.SEND_COMMAND, otherValue), "CANDIDATE_REQUEST_MISMATCH");

        var otherTarget = new HashMap<>(real); otherTarget.put("deviceId", other.toString());
        denied(p, new ActionExecutionRequest(p.requestId(), p.matchId(), ActionType.SEND_COMMAND, otherTarget), "CANDIDATE_REQUEST_MISMATCH");

        var extra = new HashMap<>(real); extra.put("note", "added");
        denied(p, new ActionExecutionRequest(p.requestId(), p.matchId(), ActionType.SEND_COMMAND, extra), "CANDIDATE_REQUEST_MISMATCH");

        var missing = new HashMap<>(real); missing.remove("command");
        denied(p, new ActionExecutionRequest(p.requestId(), p.matchId(), ActionType.SEND_COMMAND, missing), "CANDIDATE_REQUEST_MISMATCH");

        denied(p, new ActionExecutionRequest(p.requestId(), p.matchId(), ActionType.CREATE_ALERT, real), "CANDIDATE_REQUEST_MISMATCH");
        denied(p, new ActionExecutionRequest(p.requestId(), p.matchId(), ActionType.WEBHOOK, real), "CANDIDATE_REQUEST_MISMATCH");
        denied(p, new ActionExecutionRequest(UUID.randomUUID(), p.matchId(), ActionType.SEND_COMMAND, real), "PERSISTED_PLAN_AUTHORITY_REQUIRED");

        assertNoEffects(p, p.device(), other);
        assertEquals("SELECTED", sql.queryForObject("SELECT disposition FROM rule_action_candidates WHERE request_id=?", String.class, p.requestId()), "the plan itself is untouched");
    }

    @Test void theExactPersistedRequestIsAuthorizedAndExecutesThroughTheNormalLifecycle() {
        Planned p = plannedLedCommand();
        var exact = new ActionExecutionRequest(p.requestId(), p.matchId(), ActionType.SEND_COMMAND, new LinkedHashMap<>(ledCommand(p.device(), true).parameters()));
        assertDoesNotThrow(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> authorization.check(exact, new ActionContext(null, p.pinned()))));
        engine.process(List.of(exact), new ActionContext(null, p.pinned()), "exact");
        assertEquals(1, count("SELECT count(*) FROM action_history WHERE request_id=?", p.requestId()));
        verify(executor, times(1)).publishPrepared(any(), any());
    }
}
