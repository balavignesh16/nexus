package com.nexus.rule.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicExplanationRendererTest {

    private final DeterministicExplanationRenderer renderer = new DeterministicExplanationRenderer();
    private final GroundingChecker groundingChecker =
            new GroundingChecker(new ObjectMapper().registerModule(new JavaTimeModule()));

    private Map<String, Object> ruleSnapshot(String name) {
        return Map.of(
                "name", name,
                "conditions", List.of(Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0"))
        );
    }

    private Map<String, Object> evaluationEvidence(Object actualValue) {
        return Map.of(
                "outcome", "matched",
                "evaluatedConditions", List.of(
                        Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0",
                                "actualValue", actualValue, "conditionMet", true)
                )
        );
    }

    private RuleMatchExplanationContext baseContext(UUID deviceId, List<ActionSummary> actions) {
        return new RuleMatchExplanationContext(
                UUID.randomUUID(), UUID.randomUUID(), ruleSnapshot("High Temperature Alert"),
                UUID.randomUUID(), "corr-1", evaluationEvidence(34.7), Instant.now(), "All conditions met",
                deviceId, deviceId != null ? new DomainEventSummary(UUID.randomUUID(), deviceId, "TWIN_UPDATED", Instant.now(), "test", "INFO") : null,
                actions
        );
    }

    @Test
    void valueGt30_actualValue34_7_scenario_producesCorrectFacts() {
        RuleMatchExplanationContext context = baseContext(UUID.randomUUID(), List.of());

        RuleMatchExplanation explanation = renderer.render(context);

        assertEquals("DETERMINISTIC_FALLBACK", explanation.generatedBy());
        assertEquals(context.matchId(), explanation.matchId());

        List<Claim> facts = explanation.claims().stream().filter(c -> c.tier() == ClaimTier.FACT).toList();
        assertTrue(facts.stream().anyMatch(c -> c.text().contains("High Temperature Alert")), "rule name fact");
        assertTrue(facts.stream().anyMatch(c -> c.text().contains("34.7")), "observed value fact");
        assertTrue(facts.stream().anyMatch(c -> c.text().contains("VALUE") && c.text().contains("GT") && c.text().contains("30.0")),
                "threshold condition fact");
    }

    @Test
    void everyEvidenceRef_resolvesViaGroundingChecker() {
        RuleMatchExplanationContext context = baseContext(UUID.randomUUID(), List.of());

        RuleMatchExplanation explanation = renderer.render(context);
        GroundingResult result = groundingChecker.check(context, explanation);

        assertEquals(0, result.rejectedClaimCount(), "every claim the renderer produces must pass grounding: " + result.rejectionReasons());
        // GroundingChecker unconditionally appends its own mandatory
        // CAUSAL_UNCERTAINTY claim on top of whatever the candidate (here,
        // the renderer's own output) already proposed - deliberate
        // redundancy, not a bug: the renderer's own claims all survive
        // (rejectedClaimCount == 0 above), plus exactly one more mandatory
        // claim (deviceId is non-null in this fixture, so DEVICE_UNKNOWN is
        // not additionally appended).
        assertEquals(explanation.claims().size() + 1, result.validated().claims().size());
    }

    @Test
    void causalUncertainty_unknownClaim_alwaysPresent() {
        RuleMatchExplanationContext context = baseContext(UUID.randomUUID(), List.of());

        RuleMatchExplanation explanation = renderer.render(context);

        assertTrue(explanation.claims().stream().anyMatch(c ->
                c.tier() == ClaimTier.UNKNOWN && c.text().toLowerCase().contains("sensor or equipment")));
    }

    @Test
    void noClaimEverAssertsPhysicalActuation() {
        RuleMatchExplanationContext context = baseContext(UUID.randomUUID(),
                List.of(new ActionSummary(UUID.randomUUID(), "SEND_COMMAND", "SUCCESS", Instant.now(), "dispatched")));

        RuleMatchExplanation explanation = renderer.render(context);

        for (Claim claim : explanation.claims()) {
            String lower = claim.text().toLowerCase();
            assertFalse(lower.contains("physically") || lower.contains("actually shut down") || lower.contains("actuated"),
                    "claim must not assert physical actuation: " + claim.text());
        }
        assertTrue(explanation.actionNote() != null && explanation.actionNote().toLowerCase().contains("not evidence"));
    }

    @Test
    void actionPresent_producesFixedDisclaimerVerbatim() {
        RuleMatchExplanationContext context = baseContext(UUID.randomUUID(),
                List.of(new ActionSummary(UUID.randomUUID(), "CREATE_ALERT", "SUCCESS", Instant.now(), "ok")));

        RuleMatchExplanation explanation = renderer.render(context);

        assertEquals(ExecutionTruthfulness.noteFor(ExecutionTruthfulness.typesOf(context)), explanation.actionNote());
    }

    @Test
    void actionAbsent_actionNoteIsNull() {
        RuleMatchExplanationContext context = baseContext(UUID.randomUUID(), List.of());

        RuleMatchExplanation explanation = renderer.render(context);

        assertNull(explanation.actionNote());
        assertTrue(explanation.claims().stream().noneMatch(c -> c.text().contains("NEXUS recorded a")));
    }

    @Test
    void missingDevice_producesDeviceUnknownClaim() {
        RuleMatchExplanationContext context = baseContext(null, List.of());

        RuleMatchExplanation explanation = renderer.render(context);

        assertTrue(explanation.claims().stream().anyMatch(c ->
                c.tier() == ClaimTier.UNKNOWN && c.text().equals(DeterministicExplanationRenderer.DEVICE_UNKNOWN)));
    }

    @Test
    void multipleEvaluatedConditions_producesFactPerCondition() {
        Map<String, Object> evidence = Map.of(
                "outcome", "matched",
                "evaluatedConditions", List.of(
                        Map.of("field", "EVENT_TYPE", "operator", "EQ", "expectedValue", "TWIN_UPDATED", "actualValue", "TWIN_UPDATED", "conditionMet", true),
                        Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0", "actualValue", 34.7, "conditionMet", true)
                )
        );
        RuleMatchExplanationContext context = new RuleMatchExplanationContext(
                UUID.randomUUID(), UUID.randomUUID(), ruleSnapshot("Multi-Condition Rule"), UUID.randomUUID(),
                "corr-multi", evidence, Instant.now(), "All conditions met", UUID.randomUUID(), null, List.of());

        RuleMatchExplanation explanation = renderer.render(context);

        long observedValueFacts = explanation.claims().stream()
                .filter(c -> c.tier() == ClaimTier.FACT && c.text().startsWith("Observed value was"))
                .count();
        assertEquals(2, observedValueFacts);
    }

    @Test
    void malformedEvaluationEvidence_doesNotCrashRenderer() {
        Map<String, Object> malformed = Map.of("outcome", "matched");
        RuleMatchExplanationContext context = new RuleMatchExplanationContext(
                UUID.randomUUID(), UUID.randomUUID(), ruleSnapshot("Malformed Evidence Rule"), UUID.randomUUID(),
                "corr-malformed", malformed, Instant.now(), "All conditions met", UUID.randomUUID(), null, List.of());

        RuleMatchExplanation explanation = renderer.render(context);

        assertFalse(explanation.claims().isEmpty(), "at least the always-present UNKNOWN causal claim must exist");
    }

    @Test
    void everyFactClaim_hasCorrectAssertedValue() {
        RuleMatchExplanationContext context = baseContext(UUID.randomUUID(),
                List.of(new ActionSummary(UUID.randomUUID(), "CREATE_ALERT", "SUCCESS", Instant.now(), "ok")));

        RuleMatchExplanation explanation = renderer.render(context);

        List<Claim> facts = explanation.claims().stream().filter(c -> c.tier() == ClaimTier.FACT).toList();
        assertFalse(facts.isEmpty());
        for (Claim fact : facts) {
            assertTrue(fact.evidenceRef() != null, "every FACT must have an evidenceRef: " + fact.text());
        }
        assertTrue(facts.stream().anyMatch(c -> "High Temperature Alert".equals(c.assertedValue())));
        assertTrue(facts.stream().anyMatch(c -> Double.valueOf(34.7).equals(c.assertedValue())));
        assertTrue(facts.stream().anyMatch(c -> "SUCCESS".equals(c.assertedValue())));
    }

    @Test
    void malformedRuleSnapshot_doesNotCrashRenderer() {
        Map<String, Object> malformedSnapshot = Map.of("enabled", true);
        RuleMatchExplanationContext context = new RuleMatchExplanationContext(
                UUID.randomUUID(), UUID.randomUUID(), malformedSnapshot, UUID.randomUUID(),
                "corr-malformed-rule", evaluationEvidence(34.7), Instant.now(), "All conditions met",
                UUID.randomUUID(), null, List.of());

        RuleMatchExplanation explanation = renderer.render(context);

        assertFalse(explanation.claims().isEmpty());
        assertTrue(explanation.claims().stream().noneMatch(c -> c.text().startsWith("Rule '")));
    }
}
