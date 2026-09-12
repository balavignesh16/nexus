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
        // own record components are exactly these five - matchId, ruleId,
        // eventId, deviceId, correlationId are not among them, so no code
        // path through this record can ever carry them to an AI provider.
        var components = AiExplanationContext.class.getRecordComponents();
        assertEquals(5, components.length);
        for (var component : components) {
            String name = component.getName();
            assertFalse(name.equals("matchId") || name.equals("ruleId") || name.equals("eventId")
                    || name.equals("deviceId") || name.equals("correlationId"));
        }
    }
}
