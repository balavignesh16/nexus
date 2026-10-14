package com.nexus.rule.trigger;

import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionType;
import com.nexus.rule.application.RuleExecutionAuthorization;
import com.nexus.rule.application.RuleExecutionSuppressedException;
import com.nexus.rule.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Ledger 25-27 style evidence for the source chain:
 * source transaction -> committed fact -> routing -> frozen context -> evaluation -> persisted match -> plan -> outcome.
 */
class RuleTypedTelemetryRoutingIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleExecutionAuthorization authorization;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @org.springframework.boot.test.mock.mockito.SpyBean RuleSourceFacts sourceFacts;

    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    @Test void acceptedTelemetryRunsTheWholePersistedChain() {
        UUID device = device();
        UUID rule = typedRule(device, "typed telemetry", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()));
        clearInvocations(alerts);
        ingest(device, 25, now.minusSeconds(5));

        assertEquals(1, facts("ACCEPTED_TELEMETRY", device));
        var match = sql.queryForMap("SELECT match_id,event_id,trigger_type,trigger_fact_id,rule_version_id,rule_revision,execution_epoch FROM rule_matches WHERE rule_id=?", rule);
        assertNull(match.get("event_id"), "no DomainEvent is fabricated for a typed source");
        assertEquals("ACCEPTED_TELEMETRY", match.get("trigger_type"));
        assertNotNull(match.get("trigger_fact_id"));
        assertNotNull(match.get("rule_version_id"));
        var plan = sql.queryForMap("SELECT p.status,c.disposition,c.reason FROM rule_action_plans p JOIN rule_action_candidates c USING(plan_id) WHERE p.fact_id=?", match.get("trigger_fact_id"));
        assertEquals("APPROVED", plan.get("status"));
        assertEquals("SELECTED", plan.get("disposition"));
        verify(alerts, times(1)).execute(any(), any());
        assertEquals("SUCCESS", sql.queryForObject("SELECT status FROM action_history WHERE match_id=?", String.class, match.get("match_id")),
            "the selected candidate executed through the guarded engine and was recorded");
        String evidence = sql.queryForObject("SELECT evaluation_evidence::text FROM rule_matches WHERE rule_id=?", String.class, rule);
        assertTrue(evidence.contains("ACCEPTED_TELEMETRY") && evidence.contains("repeatable read"), evidence);
    }

    @Test void replayOutOfOrderAndFutureObservationsAreNotAcceptedTelemetry() {
        UUID device = device();
        UUID rule = typedRule(device, "typed replay", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()));
        Instant base = now.minusSeconds(30);
        ingest(device, 25, base);
        assertEquals(1, facts("ACCEPTED_TELEMETRY", device));
        ingest(device, 26, base);                       // exact replay of the stored timestamp
        ingest(device, 27, base.minusSeconds(10));      // older than the stored observation
        ingest(device, 28, now.plusSeconds(3600));      // beyond the future-skew allowance
        assertEquals(1, facts("ACCEPTED_TELEMETRY", device), "rejected observations leave history rows but no accepted-observation fact");
        assertEquals(1, matches(rule));
        ingest(device, 29, base.plusSeconds(5));        // a newer valid observation is accepted
        assertEquals(2, facts("ACCEPTED_TELEMETRY", device));
        assertEquals(2, matches(rule));
    }

    @Test void sameValueAcceptedTelemetryIsNotASemanticTwinChange() {
        UUID device = device();
        UUID changed = typedRule(device, "twin changed", 1, List.of(new TriggerSubscription(TriggerType.TWIN_STATE_CHANGED)), List.of(temperatureAbove("10")), List.of(alert()));
        UUID accepted = typedRule(device, "accepted", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()));
        ingest(device, 25, now.minusSeconds(20));
        ingest(device, 25, now.minusSeconds(10));       // same value, newer accepted observation
        ingest(device, 26, now.minusSeconds(5));        // semantic change
        assertEquals(3, facts("ACCEPTED_TELEMETRY", device));
        assertEquals(2, facts("TWIN_STATE_CHANGED", device), "creation and the 25 -> 26 change only: "+sql.queryForList("SELECT transition_type,source,new_numeric_value,previous_numeric_value FROM twin_state_transitions WHERE device_id=?", device)+sql.queryForList("SELECT trigger_type,source_key FROM rule_trigger_facts WHERE device_id=?", device));
        assertEquals(3, matches(accepted));
        assertEquals(2, matches(changed));
    }

    @Test void legacyAndTypedSubscriptionsStaySeparate() {
        UUID device = device();
        UUID typed = typedRule(device, "typed only", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()));
        UUID legacy = UUID.randomUUID();
        lifecycle.legacySave(new Rule(legacy, "imported legacy", true, 2, List.of(
            new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()),
            new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED")), List.of(alert()), Map.of()), null);
        ingest(device, 25, now.minusSeconds(5));
        assertEquals(1, matches(typed));
        assertEquals(0, count("SELECT count(*) FROM rule_matches WHERE rule_id=? AND event_id IS NULL", legacy), "legacy rule never receives typed facts");
        assertEquals(0, count("SELECT count(*) FROM rule_matches WHERE rule_id=? AND event_id IS NOT NULL", typed), "typed rule never receives legacy events");
        assertEquals(1, count("SELECT count(*) FROM rule_matches WHERE rule_id=? AND event_id IS NOT NULL AND trigger_fact_id IS NULL", legacy));
        assertEquals(0, count("SELECT count(*) FROM rule_action_candidates WHERE rule_id=?", legacy), "legacy matches keep the C13 exception, not typed plans");
    }

    @Test void typedActionWithoutAPersistedPlanCandidateIsDenied() {
        UUID device = device();
        UUID rule = typedRule(device, "plan gate", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()));
        ingest(device, 25, now.minusSeconds(5));
        UUID matchId = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, rule);
        Rule pinned = new Rule(rule, "x", true, 1, List.of(), List.of(alert()), Map.of(), null,
            new RuleExecutionIdentity(sql.queryForObject("SELECT rule_version_id FROM rule_matches WHERE match_id=?", UUID.class, matchId),
                sql.queryForObject("SELECT rule_revision FROM rule_matches WHERE match_id=?", Long.class, matchId),
                sql.queryForObject("SELECT execution_epoch FROM rule_matches WHERE match_id=?", Long.class, matchId)));
        var forged = new ActionExecutionRequest(UUID.randomUUID(), matchId, ActionType.CREATE_ALERT, Map.of());
        var thrown = assertThrows(RuleExecutionSuppressedException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(
            tx -> authorization.check(forged, new ActionContext(null, pinned))));
        assertEquals("PERSISTED_PLAN_AUTHORITY_REQUIRED", thrown.getMessage());
        UUID realRequest = sql.queryForObject("SELECT request_id FROM rule_action_candidates WHERE match_id=?", UUID.class, matchId);
        assertDoesNotThrow(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> authorization.check(
            new ActionExecutionRequest(realRequest, matchId, ActionType.CREATE_ALERT, Map.of("severity", "INFO")), new ActionContext(null, pinned))));
        // Numerically equal values compare as values, not as text.
        assertDoesNotThrow(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> authorization.check(
            new ActionExecutionRequest(realRequest, matchId, ActionType.CREATE_ALERT, new java.util.LinkedHashMap<>(Map.of("severity", "INFO"))), new ActionContext(null, pinned))));
    }

    @Test void failedPlanPersistenceDispatchesNothingButKeepsTheMatch() {
        UUID device = device();
        UUID rule = typedRule(device, "plan failure", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()));
        clearInvocations(alerts);
        doThrow(new IllegalStateException("injected plan outage")).when(planStore).persist(any(), any());
        try { ingest(device, 25, now.minusSeconds(5)); } finally { reset(planStore); }
        assertEquals(1, matches(rule), "the attributed match is history regardless of plan outcome");
        assertEquals(0, count("SELECT count(*) FROM rule_action_candidates WHERE rule_id=?", rule));
        verify(alerts, never()).execute(any(), any());
    }

    @Test void failedSourceFactPersistenceRollsBackAcceptanceAndDispatchesNothing() {
        UUID device = device();
        UUID rule = typedRule(device, "fact failure", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()));
        clearInvocations(alerts);
        doThrow(new IllegalStateException("injected fact outage")).when(sourceFacts).acceptedTelemetry(any(), any(), any(), any());
        try { assertThrows(RuntimeException.class, () -> ingest(device, 25, now.minusSeconds(5))); } finally { reset(sourceFacts); }
        assertEquals(0, matches(rule));
        assertEquals(0, count("SELECT count(*) FROM digital_twins WHERE device_id=?", device), "the source change committed atomically with its fact or not at all");
        verify(alerts, never()).execute(any(), any());
    }

    @Test void duplicateRoutingOfOneFactCreatesNoSecondDecision() {
        UUID device = device();
        UUID rule = typedRule(device, "idempotent", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()));
        clearInvocations(alerts);
        ingest(device, 25, now.minusSeconds(5));
        UUID fact = sql.queryForObject("SELECT fact_id FROM rule_trigger_facts WHERE trigger_type='ACCEPTED_TELEMETRY' AND device_id=?", UUID.class, device);
        router.route(fact);
        router.route(fact);
        assertEquals(1, matches(rule), "unique (rule, version, fact) rejects duplicate decisions");
        assertEquals(1, count("SELECT count(*) FROM rule_action_plans WHERE fact_id=?", fact));
        verify(alerts, times(1)).execute(any(), any());
    }
}
