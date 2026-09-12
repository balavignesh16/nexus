package com.nexus.rule.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the full provider-independent chain end-to-end (Tier 4 Phase 2A):
 *
 *   RuleMatchExplanationContext
 *       -> AiExplanationContextMapper -> AiExplanationContext
 *       -> AiReasoningBoundary (fake) -> candidate RuleMatchExplanation
 *       -> GroundingChecker -> validated explanation
 *
 * No real provider is involved anywhere in this test.
 */
class AiReasoningBoundaryEndToEndTest {

    private final AiExplanationContextMapper mapper = new AiExplanationContextMapper();
    private final GroundingChecker groundingChecker = new GroundingChecker(new ObjectMapper().registerModule(new JavaTimeModule()));

    private RuleMatchExplanationContext sampleContext() {
        return new RuleMatchExplanationContext(
                UUID.randomUUID(), UUID.randomUUID(),
                Map.of("name", "High Temperature Alert",
                        "conditions", List.of(Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0"))),
                UUID.randomUUID(), "corr-e2e",
                Map.of("outcome", "matched", "evaluatedConditions", List.of(
                        Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0",
                                "actualValue", 34.7, "conditionMet", true))),
                Instant.now(), "All conditions met",
                UUID.randomUUID(), null,
                List.of(new ActionSummary(UUID.randomUUID(), "CREATE_ALERT", "SUCCESS", Instant.now(), "ok")));
    }

    @Test
    void wellFormedFakeCandidate_flowsThroughToValidatedAiExplanation() {
        RuleMatchExplanationContext context = sampleContext();
        AiExplanationContext aiContext = mapper.toAiContext(context);

        AiReasoningBoundary boundary = new FakeAiReasoningBoundary(ctx -> new RuleMatchExplanation(
                context.matchId(), "The rule matched because the observed value exceeded the threshold.",
                List.of(new Claim(ClaimTier.FACT, "Observed value was 34.7.",
                        "evaluationEvidence.evaluatedConditions[0].actualValue", 34.7, List.of())),
                null, "AI"));

        RuleMatchExplanation candidate = boundary.generate(aiContext);
        GroundingResult result = groundingChecker.check(context, candidate);

        assertEquals(0, result.rejectedClaimCount());
        assertTrue(result.validated().claims().stream().anyMatch(c -> c.text().contains("34.7")));
        assertTrue(result.validated().claims().stream().anyMatch(c ->
                c.text().equals(DeterministicExplanationRenderer.CAUSAL_UNCERTAINTY)));
        assertEquals(DeterministicExplanationRenderer.ACTION_DISCLAIMER, result.validated().actionNote());
        assertEquals("AI", result.validated().generatedBy());
    }

    @Test
    void fakeCandidateWithWrongAssertedValue_rejectedByGroundingChecker() {
        RuleMatchExplanationContext context = sampleContext();
        AiExplanationContext aiContext = mapper.toAiContext(context);

        AiReasoningBoundary boundary = new FakeAiReasoningBoundary(ctx -> new RuleMatchExplanation(
                context.matchId(), "summary",
                List.of(new Claim(ClaimTier.FACT, "Observed value was 40.2.",
                        "evaluationEvidence.evaluatedConditions[0].actualValue", 40.2, List.of())),
                null, "AI"));

        RuleMatchExplanation candidate = boundary.generate(aiContext);
        GroundingResult result = groundingChecker.check(context, candidate);

        assertEquals(1, result.rejectedClaimCount());
        assertTrue(result.validated().claims().isEmpty(), "zero surviving claims signals the caller must use the deterministic fallback");
    }

    @Test
    void fakeCandidateAttemptingPhysicalActuationClaim_hasNoFieldToCite_rejected() {
        RuleMatchExplanationContext context = sampleContext();
        AiExplanationContext aiContext = mapper.toAiContext(context);

        AiReasoningBoundary boundary = new FakeAiReasoningBoundary(ctx -> new RuleMatchExplanation(
                context.matchId(), "summary",
                List.of(new Claim(ClaimTier.FACT, "The actuator physically moved.",
                        "actions[0].physicalOutcome", "MOVED", List.of())),
                null, "AI"));

        RuleMatchExplanation candidate = boundary.generate(aiContext);
        GroundingResult result = groundingChecker.check(context, candidate);

        assertEquals(1, result.rejectedClaimCount(), "no physicalOutcome field exists anywhere in the context to cite");
    }

    @Test
    void aiContextHandedToBoundary_containsNoForbiddenIdentifiers() {
        RuleMatchExplanationContext context = sampleContext();
        AiExplanationContext aiContext = mapper.toAiContext(context);

        assertTrue(!aiContext.ruleSnapshot().containsKey("ruleId"));
        // matchId/ruleId/eventId/deviceId/correlationId simply have no
        // record component on AiExplanationContext at all (structurally
        // proven in AiExplanationContextMapperTest); this test additionally
        // confirms the one nested location (ruleSnapshot.ruleId) is stripped.
    }
}
