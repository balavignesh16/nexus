package com.nexus.rule.trigger;

import com.nexus.action.application.ActionExecutionEngine;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionType;
import com.nexus.rule.application.RuleExecutionAuthorization;
import com.nexus.rule.application.RuleExecutionSuppressedException;
import com.nexus.rule.application.RuleMatcher;
import com.nexus.rule.domain.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * C13 retirement (scenario 66): legacy DomainEvent matches take the SAME persisted arbitration pathway as typed ones -
 * candidates, deterministic arbitration, an approved persisted plan, selected-candidate authorization, guarded acceptance. There is
 * no independent per-match dispatch left, and every outcome (selected, coalesced, suppressed, denied) is persisted truthfully.
 */
class RuleLegacyUnifiedArbitrationIntegrationTest extends TypedTriggerSupport {
    @Autowired ApplicationContext context;
    @Autowired RuleExecutionAuthorization authorization;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.nexus.rule.persistence.RuleVersionStore versionStore;
    @SpyBean ActionExecutionEngine engine;

    @AfterEach void restore() { reset(planStore); reset(engine); }

    private UUID legacyRule(UUID device, String name, int priority, RuleAction... actions) {
        UUID id = UUID.randomUUID();
        lifecycle.legacySave(new Rule(id, name, true, priority, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"), temperatureAbove("10")),
            List.of(actions), Map.of(), null), null);
        return id;
    }
    private UUID eventOf(UUID rule) { return sql.queryForObject("SELECT event_id FROM rule_matches WHERE rule_id=? ORDER BY matched_at DESC LIMIT 1", UUID.class, rule); }
    private int commands(UUID device) { return count("SELECT count(*) FROM action_history WHERE device_id=? AND action_type='SEND_COMMAND'", device); }
    private Map<String, Object> candidate(UUID rule) {
        return sql.queryForMap("SELECT c.disposition,c.reason,c.winner_candidate_id,c.outcome,c.outcome_reason,c.request_id,c.candidate_id,c.evidence::text AS evidence FROM rule_action_candidates c WHERE c.rule_id=? ORDER BY c.action_ordinal LIMIT 1", rule);
    }
    /** Replays the dispatch of a persisted candidate straight at guarded acceptance, as a forged or stale caller would. */
    private String authorizationOutcome(UUID rule, UUID device, Map<String, Object> parameters) {
        UUID matchId = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=? ORDER BY matched_at DESC LIMIT 1", UUID.class, rule);
        UUID requestId = (UUID) candidate(rule).get("request_id");
        var pinned = versionStore.version(rule, versionStore.get(rule).orElseThrow().activeVersionId());
        var owner = sql.queryForMap("SELECT rule_version_id,rule_revision,execution_epoch FROM rule_matches WHERE match_id=?", matchId);
        var identity = new RuleExecutionIdentity((UUID) owner.get("rule_version_id"), ((Number) owner.get("rule_revision")).longValue(), ((Number) owner.get("execution_epoch")).longValue());
        var rulePinned = new Rule(rule, pinned.name(), true, 1, pinned.conditions(), pinned.actions(), Map.of(), null, identity, pinned.runtime());
        try {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> authorization.check(new ActionExecutionRequest(requestId, matchId, ActionType.SEND_COMMAND, parameters), new ActionContext(null, rulePinned)));
            return "AUTHORIZED";
        } catch (RuleExecutionSuppressedException denied) { return denied.getMessage(); }
    }

    private int plansForEvent(UUID event) { return count("SELECT count(*) FROM rule_action_plans WHERE event_id=?", event); }

    @Test void aLegacyCommandIsDecidedByAPersistedPlanBeforeItIsAccepted() {
        UUID device = device();
        UUID rule = legacyRule(device, "legacy command", 1, ledCommand(device, true));
        ingest(device, 25, now);
        UUID event = eventOf(rule);
        assertEquals(1, plansForEvent(event), "one persisted plan keyed by the committed DomainEvent");
        assertEquals(0, count("SELECT count(*) FROM rule_action_plans WHERE event_id=? AND fact_id IS NOT NULL", event));
        var c = candidate(rule);
        assertEquals("SELECTED", c.get("disposition"));
        assertEquals(1, commands(device));
        assertEquals(c.get("request_id"), sql.queryForObject("SELECT request_id FROM action_history WHERE device_id=? AND action_type='SEND_COMMAND'", UUID.class, device),
            "the accepted command carries the persisted candidate's request id");
        assertEquals(sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, rule),
            sql.queryForObject("SELECT match_id FROM action_history WHERE device_id=? AND action_type='SEND_COMMAND'", UUID.class, device), "and the legitimate primary match");
        assertEquals("ACCEPTED", c.get("outcome"), "acceptance is recorded as evidence only");
        assertTrue(c.get("evidence").toString().contains("\"safetyOverride\": \"UNSUPPORTED\""));
        verify(executor, times(1)).publishPrepared(any(), any());
    }

    @Test void noIndependentLegacyDispatcherRemainsAnywhereInTheApplication() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.nexus.action.application.ActionPlanner"), "the C13 compatibility dispatcher is gone");
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type == null || !type.getName().startsWith("com.nexus")) continue;
            for (Method method : type.getDeclaredMethods())
                if (method.isAnnotationPresent(EventListener.class))
                    for (Class<?> parameter : method.getParameterTypes())
                        assertNotEquals(RuleMatchedEvent.class, parameter, type.getSimpleName() + "." + method.getName() + " would dispatch a match outside the plan");
        }
        // Behavioral proof: announcing a persisted legacy match on the event bus (what the old listener consumed) accepts nothing.
        UUID device = device();
        UUID rule = legacyRule(device, "announced only", 1, ledCommand(device, true));
        doNothing().when(engine).process(any(), any(), any());
        ingest(device, 25, now);
        reset(engine);
        assertEquals(0, commands(device));
        var match = sql.queryForMap("SELECT match_id,event_id FROM rule_matches WHERE rule_id=?", rule);
        var pinned = versionStore.version(rule, versionStore.get(rule).orElseThrow().activeVersionId());
        context.publishEvent(new RuleMatchedEvent((UUID) match.get("match_id"), now, new RuleEvaluationResult(true, pinned, null, "announced"), "c"));
        assertEquals(0, commands(device), "no listener turns a match into a command");
        verify(executor, never()).publishPrepared(any(), any());
    }

    @Test void legacyRulesAreArbitratedByPriorityAndEqualPriorityConflictsDispatchNothing() {
        UUID device = device();
        UUID high = legacyRule(device, "legacy on", 1, ledCommand(device, true));
        UUID low = legacyRule(device, "legacy off", 2, ledCommand(device, false));
        ingest(device, 25, now);
        assertEquals("SELECTED", candidate(high).get("disposition"));
        assertEquals("SUPPRESSED_PRIORITY", candidate(low).get("disposition"));
        assertEquals(candidate(high).get("candidate_id"), candidate(low).get("winner_candidate_id"));
        assertEquals(1, commands(device), "only the priority winner reached guarded acceptance");
        assertEquals("PERSISTED_PLAN_AUTHORITY_REQUIRED", authorizationOutcome(low, device, ledCommand(device, false).parameters()),
            "a SUPPRESSED candidate's request cannot be authorized even if a caller replays it");

        UUID device2 = device();
        UUID a = legacyRule(device2, "tie a", 5, ledCommand(device2, true));
        UUID b = legacyRule(device2, "tie b", 5, ledCommand(device2, false));
        now = now.plusSeconds(1);
        ingest(device2, 25, now);
        assertEquals("SUPPRESSED_CONFLICT", candidate(a).get("disposition"));
        assertEquals("SUPPRESSED_CONFLICT", candidate(b).get("disposition"));
        assertEquals(0, commands(device2), "an equal-priority conflict never picks a random winner");
        assertEquals("PERSISTED_PLAN_AUTHORITY_REQUIRED", authorizationOutcome(a, device2, ledCommand(device2, true).parameters()));
        assertEquals("PERSISTED_PLAN_AUTHORITY_REQUIRED", authorizationOutcome(b, device2, ledCommand(device2, false).parameters()));
        assertEquals(1, count("SELECT count(*) FROM rule_matches WHERE rule_id=?", a), "both matches are still attributed history");
    }

    @Test void identicalLegacyCommandsCoalesceIntoOneCommandWithEveryContributorRecorded() {
        UUID device = device();
        UUID first = legacyRule(device, "coalesce 1", 1, ledCommand(device, true));
        UUID second = legacyRule(device, "coalesce 2", 2, ledCommand(device, true));
        ingest(device, 25, now);
        assertEquals(1, commands(device), "one command for one identical target/value");
        var dispositions = new HashSet<>(List.of(candidate(first).get("disposition"), candidate(second).get("disposition")));
        assertEquals(Set.of("SELECTED", "COALESCED"), dispositions);
        assertEquals(1, count("SELECT count(*) FROM rule_matches WHERE rule_id IN (?,?)", first, second) / 2, "both rules keep their own match");
        verify(executor, times(1)).publishPrepared(any(), any());
    }

    @Test void legacyAndTypedBatchesAreDecidedInTheirOwnPlansWithNoGlobalPriorityClaim() {
        UUID device = device();
        UUID legacy = legacyRule(device, "legacy batch", 1, ledCommand(device, true));
        UUID typed = typedRule(device, "typed batch", 2, List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY)), List.of(temperatureAbove("10")), List.of(ledCommand(device, false)));
        ingest(device, 25, now);
        assertEquals(1, plansForEvent(eventOf(legacy)));
        assertEquals(1, count("SELECT count(*) FROM rule_action_plans p JOIN rule_matches m ON m.trigger_fact_id=p.fact_id WHERE m.rule_id=?", typed));
        assertEquals("SELECTED", candidate(legacy).get("disposition"));
        assertEquals("SELECTED", candidate(typed).get("disposition"), "arbitration covers one trigger batch; the batches are independent plans (C16: no cross-batch global priority)");
    }

    @Test void aFailedPlanPersistenceDispatchesNothingForALegacyMatch() {
        UUID device = device();
        UUID rule = legacyRule(device, "plan outage", 1, ledCommand(device, true));
        doThrow(new IllegalStateException("injected plan outage")).when(planStore).persist(any(), any());
        ingest(device, 25, now);
        assertEquals(1, count("SELECT count(*) FROM rule_matches WHERE rule_id=?", rule), "the attributed match is history");
        assertEquals(0, plansForEvent(eventOf(rule)));
        assertEquals(0, commands(device));
        assertEquals(0, count("SELECT count(*) FROM current_desired_twin_state WHERE device_id=?", device));
        verify(executor, never()).publishPrepared(any(), any());
    }

    @Test void aRedeliveredLegacyEventReusesItsPlanAndNeverAcceptsASecondCommand() {
        UUID device = device();
        UUID rule = legacyRule(device, "redelivered", 1, ledCommand(device, true));
        ingest(device, 25, now);
        UUID event = eventOf(rule);
        assertEquals(1, commands(device));
        var duplicate = new com.nexus.event.domain.DomainEvent(event, com.nexus.event.domain.EventType.TWIN_UPDATED, device, now, "test", Map.of(), "INFO", "redelivered");
        context.getBean(RuleMatcher.class).onEvent(duplicate);
        assertEquals(1, plansForEvent(event), "one batch, one decision");
        assertEquals(1, commands(device), "redelivery cannot dispatch again");
        verify(executor, times(1)).publishPrepared(any(), any());
    }

    @Test void pausingBetweenTheLegacyPlanAndAcceptanceDeniesAndRecordsTheDenial() {
        UUID device = device();
        UUID rule = legacyRule(device, "legacy pause race", 1, ledCommand(device, true));
        doAnswer(inv -> {
            Object planned = inv.callRealMethod();
            lifecycle.transition(rule, "pause", null);            // ownership is lost after the plan is durable
            return planned;
        }).when(planStore).persist(any(), any());
        ingest(device, 25, now);
        var c = candidate(rule);
        assertEquals("SELECTED", c.get("disposition"), "the decision itself stands as history");
        assertEquals(0, commands(device));
        assertEquals("DENIED", c.get("outcome"));
        assertTrue(c.get("outcome_reason").toString().contains("RULE_NOT_ACTIVE"), c.get("outcome_reason").toString());
        assertEquals(0, count("SELECT count(*) FROM current_desired_twin_state WHERE device_id=?", device));
        verify(executor, never()).publishPrepared(any(), any());
    }

    @Test void aForgedLegacyRequestCannotCarryAnotherValueOrTargetUnderAValidRequestId() {
        UUID device = device();
        UUID rule = legacyRule(device, "legacy forgery", 1, ledCommand(device, true));
        doNothing().when(engine).process(any(), any(), any());
        ingest(device, 25, now);
        reset(engine);
        UUID matchId = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, rule);
        UUID requestId = (UUID) candidate(rule).get("request_id");
        var pinned = versionStore.version(rule, versionStore.get(rule).orElseThrow().activeVersionId());
        var owner = sql.queryForMap("SELECT rule_version_id,rule_revision,execution_epoch FROM rule_matches WHERE match_id=?", matchId);
        var identity = new RuleExecutionIdentity((UUID) owner.get("rule_version_id"), ((Number) owner.get("rule_revision")).longValue(), ((Number) owner.get("execution_epoch")).longValue());
        var rulePinned = new Rule(rule, pinned.name(), true, 1, pinned.conditions(), pinned.actions(), Map.of(), null, identity, pinned.runtime());
        var tampered = new HashMap<>(ledCommand(device, true).parameters()); tampered.put("value", false);
        var thrown = assertThrows(RuleExecutionSuppressedException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(
            tx -> authorization.check(new ActionExecutionRequest(requestId, matchId, ActionType.SEND_COMMAND, tampered), new ActionContext(null, rulePinned))));
        assertEquals("CANDIDATE_REQUEST_MISMATCH", thrown.getMessage());
        assertDoesNotThrow(() -> new TransactionTemplate(transactions).executeWithoutResult(
            tx -> authorization.check(new ActionExecutionRequest(requestId, matchId, ActionType.SEND_COMMAND, new LinkedHashMap<>(ledCommand(device, true).parameters())), new ActionContext(null, rulePinned))));
        assertEquals(0, commands(device));
    }

    @Test void aLegacyNonCommandActionIsPlannedAndExecutedWithItsTriggeringEvent() {
        UUID device = device();
        UUID rule = legacyRule(device, "legacy alert", 1, alert());
        clearInvocations(alerts);
        ingest(device, 25, now);
        var c = candidate(rule);
        assertEquals("SELECTED", c.get("disposition"));
        assertEquals("INDEPENDENT_TARGET", c.get("reason"));
        verify(alerts, times(1)).execute(any(), argThat(ctx -> ctx.triggeringEvent() != null && device.equals(ctx.triggeringEvent().deviceId())));
        assertEquals("EXECUTED", candidate(rule).get("outcome"), "a non-command executor that reported SUCCESS is EXECUTED, not 'accepted'");
    }
}
