package com.nexus.rule.ai;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves AiExplanationContextMapper - the single reviewable place deciding
 * what data leaves NEXUS for AI reasoning - never lets a forbidden
 * identifier or field through (Tier 4 Phase 2A).
 */
class AiExplanationContextMapperTest {

    private final AiExplanationContextMapper mapper = new AiExplanationContextMapper();

    @Test
    void sanitizedContext_doesNotContainRuleIdInsideRuleSnapshot() {
        UUID ruleId = UUID.randomUUID();
        Map<String, Object> ruleSnapshot = Map.of(
                "ruleId", ruleId.toString(),
                "executionIdentity", Map.of("ruleVersionId",UUID.randomUUID().toString(),"ruleRevision",1,"executionEpoch",3),
                "name", "High Temperature Alert",
                "enabled", true,
                "priority", 1,
                "conditions", List.of(Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0")),
                "actions", List.of(),
                "metadata", Map.of()
        );
        RuleMatchExplanationContext context = new RuleMatchExplanationContext(
                UUID.randomUUID(), ruleId, ruleSnapshot, UUID.randomUUID(), "corr-1",
                Map.of("outcome", "matched"), Instant.now(), "All conditions met",
                UUID.randomUUID(), null, List.of());

        AiExplanationContext aiContext = mapper.toAiContext(context);

        assertFalse(aiContext.ruleSnapshot().containsKey("ruleId"), "ruleId must never leave NEXUS, even nested inside ruleSnapshot");
        assertFalse(aiContext.ruleSnapshot().containsKey("executionIdentity"),"Internal version/epoch identity must not leave NEXUS");
        assertEquals("High Temperature Alert", aiContext.ruleSnapshot().get("name"), "the rest of the snapshot must survive intact");
    }

    @Test
    void sanitizedContext_actionsExposeOnlyActionTypeAndExecutorRecordedStatus() {
        UUID requestId = UUID.randomUUID();
        RuleMatchExplanationContext context = new RuleMatchExplanationContext(
                UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "Rule"), UUID.randomUUID(), "corr-1",
                Map.of("outcome", "matched"), Instant.now(), "All conditions met",
                UUID.randomUUID(), null,
                List.of(new ActionSummary(requestId, "CREATE_ALERT", "SUCCESS", Instant.now(), "internal message")));

        AiExplanationContext aiContext = mapper.toAiContext(context);

        assertEquals(1, aiContext.actions().size());
        AiActionSummary action = aiContext.actions().get(0);
        assertEquals("CREATE_ALERT", action.actionType());
        assertEquals("SUCCESS", action.executorRecordedStatus());
    }

    @Test
    void sanitizedContext_requiredFieldsSurvive() {
        Instant matchedAt = Instant.parse("2026-09-12T10:00:00Z");
        RuleMatchExplanationContext context = new RuleMatchExplanationContext(
                UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "Rule"), UUID.randomUUID(), "corr-1",
                Map.of("outcome", "matched"), matchedAt, "All conditions met",
                UUID.randomUUID(), null, List.of());

        AiExplanationContext aiContext = mapper.toAiContext(context);

        assertEquals(matchedAt, aiContext.matchedAt());
        assertEquals("All conditions met", aiContext.message());
        assertEquals("matched", aiContext.evaluationEvidence().get("outcome"));
    }

    @Test
    void sanitizedContext_noActions_producesEmptyList() {
        RuleMatchExplanationContext context = new RuleMatchExplanationContext(
                UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "Rule"), UUID.randomUUID(), "corr-1",
                Map.of("outcome", "matched"), Instant.now(), "All conditions met",
                UUID.randomUUID(), null, List.of());

        AiExplanationContext aiContext = mapper.toAiContext(context);

        assertTrue(aiContext.actions().isEmpty());
    }

    @Test
    void aiExplanationContext_hasNoFieldForMatchIdRuleIdEventIdDeviceIdOrCorrelationId() {
        // Structural proof, not just a runtime check: AiExplanationContext's
        // own record components are exactly these seven (ruleSnapshot, evaluationEvidence, matchedAt, message, actions and the persisted
        // version/candidates added for P3.6) - matchId, ruleId, eventId, deviceId, correlationId are not among them, so no code
        // path through this record can ever carry them to an AI provider. The nested AI records are checked the same way.
        var components = AiExplanationContext.class.getRecordComponents();
        assertEquals(7, components.length);
        assertEquals(java.util.List.of("ruleSnapshot", "evaluationEvidence", "matchedAt", "message", "actions", "version", "candidates"),
                java.util.Arrays.stream(components).map(c -> c.getName()).toList());
        for (Class<?> type : java.util.List.of(AiExplanationContext.class, AiCandidateSummary.class, AiVersionAttribution.class, AiActionSummary.class)) {
            for (var component : type.getRecordComponents()) {
                String name = component.getName().toLowerCase();
                assertFalse(name.endsWith("id") || name.equals("targetkey") || name.equals("deviceid") || name.contains("correlation") || name.contains("request"),
                        type.getSimpleName() + "." + component.getName() + " would leak an identifier");
            }
        }
    }
}
