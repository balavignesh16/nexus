package com.nexus.rule.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundingCheckerTest {

    private GroundingChecker checker;
    private RuleMatchExplanationContext context;

    @BeforeEach
    void setUp() {
        // A bare ObjectMapper (no Spring context in this unit test) needs
        // JavaTimeModule registered explicitly to handle Instant fields -
        // Spring Boot's auto-configured bean, used in production, already
        // has this registered by default.
        checker = new GroundingChecker(new ObjectMapper().registerModule(new JavaTimeModule()));

        Map<String, Object> ruleSnapshot = Map.of(
                "name", "High Temperature Alert",
                "conditions", List.of(Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0"))
        );
        Map<String, Object> evaluationEvidence = Map.of(
                "outcome", "matched",
                "evaluatedConditions", List.of(
                        Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0", "actualValue", 34.7, "conditionMet", true)
                )
        );

        context = new RuleMatchExplanationContext(
                UUID.randomUUID(), UUID.randomUUID(), ruleSnapshot, UUID.randomUUID(), "corr-1",
                evaluationEvidence, Instant.now(), "All conditions met",
                UUID.randomUUID(), null, List.of(
                        new ActionSummary(UUID.randomUUID(), "CREATE_ALERT", "SUCCESS", Instant.now(), "ok")
                ));
    }

    private RuleMatchExplanation explanationWith(Claim... claims) {
        return new RuleMatchExplanation(context.matchId(), "summary", List.of(claims), null, "TEST");
    }

    @Test
    void validFact_accepted() {
        Claim fact = new Claim(ClaimTier.FACT, "Observed value was 34.7.",
                "evaluationEvidence.evaluatedConditions[0].actualValue", List.of());

        GroundingResult result = checker.check(context, explanationWith(fact));

        assertEquals(1, result.validated().claims().size());
        assertEquals(0, result.rejectedClaimCount());
    }

    @Test
    void factMissingEvidenceRef_rejected() {
        Claim fact = new Claim(ClaimTier.FACT, "Observed value was 34.7.", null, List.of());

        GroundingResult result = checker.check(context, explanationWith(fact));

        assertTrue(result.validated().claims().isEmpty());
        assertEquals(1, result.rejectedClaimCount());
    }

    @Test
    void factInvalidEvidenceRef_rejected() {
        Claim fact = new Claim(ClaimTier.FACT, "Observed value was 999.", "evaluationEvidence.nonExistentField", List.of());

        GroundingResult result = checker.check(context, explanationWith(fact));

        assertTrue(result.validated().claims().isEmpty());
        assertEquals(1, result.rejectedClaimCount());
    }

    @Test
    void unknownWithoutEvidence_accepted() {
        Claim unknown = new Claim(ClaimTier.UNKNOWN, "Cannot be determined.", null, List.of());

        GroundingResult result = checker.check(context, explanationWith(unknown));

        assertEquals(1, result.validated().claims().size());
    }

    @Test
    void unknownWithEvidenceRef_rejected() {
        Claim unknown = new Claim(ClaimTier.UNKNOWN, "Cannot be determined.", "matchedAt", List.of());

        GroundingResult result = checker.check(context, explanationWith(unknown));

        assertTrue(result.validated().claims().isEmpty());
    }

    @Test
    void unknownWithSupportingRefs_rejected() {
        Claim unknown = new Claim(ClaimTier.UNKNOWN, "Cannot be determined.", null, List.of("matchedAt"));

        GroundingResult result = checker.check(context, explanationWith(unknown));

        assertTrue(result.validated().claims().isEmpty());
    }

    @Test
    void validInference_accepted() {
        Claim inference = new Claim(ClaimTier.INFERENCE,
                "The threshold violation likely triggered the recorded action.",
                null, List.of("evaluationEvidence.evaluatedConditions[0].actualValue", "actions[0].status"));

        GroundingResult result = checker.check(context, explanationWith(inference));

        assertEquals(1, result.validated().claims().size());
    }

    @Test
    void inferenceWithEvidenceRef_rejected() {
        Claim inference = new Claim(ClaimTier.INFERENCE, "Likely cause.", "matchedAt",
                List.of("evaluationEvidence.evaluatedConditions[0].actualValue"));

        GroundingResult result = checker.check(context, explanationWith(inference));

        assertTrue(result.validated().claims().isEmpty());
    }

    @Test
    void inferenceWithEmptySupportingRefs_rejected() {
        Claim inference = new Claim(ClaimTier.INFERENCE, "Likely cause.", null, List.of());

        GroundingResult result = checker.check(context, explanationWith(inference));

        assertTrue(result.validated().claims().isEmpty());
    }

    @Test
    void inferenceWithInvalidSupportingRef_rejected() {
        Claim inference = new Claim(ClaimTier.INFERENCE, "Likely cause.", null, List.of("nonExistent.path"));

        GroundingResult result = checker.check(context, explanationWith(inference));

        assertTrue(result.validated().claims().isEmpty());
    }

    @Test
    void matchIdMismatch_rejectedEntirely() {
        RuleMatchExplanation wrongMatch = new RuleMatchExplanation(
                UUID.randomUUID(), "summary",
                List.of(new Claim(ClaimTier.FACT, "x", "matchedAt", List.of())),
                null, "TEST");

        GroundingResult result = checker.check(context, wrongMatch);

        assertTrue(result.validated().claims().isEmpty());
        assertEquals(context.matchId(), result.validated().matchId());
        assertEquals(1, result.rejectedClaimCount());
    }

    @Test
    void blankClaimText_rejected() {
        Claim blank = new Claim(ClaimTier.FACT, "   ", "matchedAt", List.of());

        GroundingResult result = checker.check(context, explanationWith(blank));

        assertTrue(result.validated().claims().isEmpty());
    }

    @Test
    void emptyClaims_flaggedAsInvalid() {
        RuleMatchExplanation empty = new RuleMatchExplanation(context.matchId(), "summary", List.of(), null, "TEST");

        GroundingResult result = checker.check(context, empty);

        assertTrue(result.rejectionReasons().stream().anyMatch(r -> r.contains("non-empty")));
    }

    @Test
    void allClaimsRejected_signalsFallbackRequired() {
        Claim badFact = new Claim(ClaimTier.FACT, "Wrong value.", "nonExistent.path", List.of());
        Claim badUnknown = new Claim(ClaimTier.UNKNOWN, "x", "matchedAt", List.of());

        GroundingResult result = checker.check(context, explanationWith(badFact, badUnknown));

        assertTrue(result.validated().claims().isEmpty(),
                "an empty validated claims list is the caller's signal that the deterministic fallback is required");
        assertEquals(2, result.rejectedClaimCount());
    }

    // ---------- Issue 1: null supportingEvidenceRefs must be rejected, not silently normalized ----------

    @Test
    void factWithNullSupportingEvidenceRefs_rejected_noException() {
        Claim fact = new Claim(ClaimTier.FACT, "Observed value was 34.7.",
                "evaluationEvidence.evaluatedConditions[0].actualValue", null);

        GroundingResult result = checker.check(context, explanationWith(fact));

        assertTrue(result.validated().claims().isEmpty());
        assertEquals(1, result.rejectedClaimCount());
        assertTrue(result.rejectionReasons().stream().anyMatch(r -> r.contains("supportingEvidenceRefs")));
    }

    @Test
    void unknownWithNullSupportingEvidenceRefs_rejected_noException() {
        Claim unknown = new Claim(ClaimTier.UNKNOWN, "Cannot be determined.", null, null);

        GroundingResult result = checker.check(context, explanationWith(unknown));

        assertTrue(result.validated().claims().isEmpty());
        assertEquals(1, result.rejectedClaimCount());
    }

    @Test
    void inferenceWithNullSupportingEvidenceRefs_rejected_noException() {
        Claim inference = new Claim(ClaimTier.INFERENCE, "Likely cause.", null, null);

        GroundingResult result = checker.check(context, explanationWith(inference));

        assertTrue(result.validated().claims().isEmpty());
        assertEquals(1, result.rejectedClaimCount());
    }

    // ---------- Issue 2: actionNote is enforced by GroundingChecker, never trusted from the candidate ----------

    private RuleMatchExplanationContext contextWithNoActions() {
        return new RuleMatchExplanationContext(
                context.matchId(), context.ruleId(), context.ruleSnapshot(), context.eventId(), context.correlationId(),
                context.evaluationEvidence(), context.matchedAt(), context.message(),
                context.deviceId(), context.domainEvent(), List.of());
    }

    private Claim groundedFact() {
        return new Claim(ClaimTier.FACT, "Observed value was 34.7.",
                "evaluationEvidence.evaluatedConditions[0].actualValue", List.of());
    }

    @Test
    void correctActionNote_actionsExist_validatedOutputHasExactDisclaimer() {
        RuleMatchExplanation candidate = new RuleMatchExplanation(
                context.matchId(), "summary", List.of(groundedFact()),
                DeterministicExplanationRenderer.ACTION_DISCLAIMER, "TEST");

        GroundingResult result = checker.check(context, candidate);

        assertEquals(DeterministicExplanationRenderer.ACTION_DISCLAIMER, result.validated().actionNote());
    }

    @Test
    void nullActionNote_actionsExist_validatedOutputStillHasExactDisclaimer() {
        RuleMatchExplanation candidate = new RuleMatchExplanation(
                context.matchId(), "summary", List.of(groundedFact()), null, "TEST");

        GroundingResult result = checker.check(context, candidate);

        assertEquals(DeterministicExplanationRenderer.ACTION_DISCLAIMER, result.validated().actionNote());
    }

    @Test
    void maliciousActionNote_actionsExist_validatedOutputReplacesItWithExactDisclaimer() {
        RuleMatchExplanation candidate = new RuleMatchExplanation(
                context.matchId(), "summary", List.of(groundedFact()),
                "The system physically actuated the device and confirmed success.", "TEST");

        GroundingResult result = checker.check(context, candidate);

        assertEquals(DeterministicExplanationRenderer.ACTION_DISCLAIMER, result.validated().actionNote());
        assertTrue(result.validated().actionNote() != null
                && !result.validated().actionNote().contains("physically actuated"));
    }

    @Test
    void actionsEmpty_candidateContainsActionNote_validatedOutputActionNoteIsNull() {
        RuleMatchExplanationContext noActionsContext = contextWithNoActions();
        RuleMatchExplanation candidate = new RuleMatchExplanation(
                noActionsContext.matchId(), "summary",
                List.of(new Claim(ClaimTier.FACT, "Observed value was 34.7.",
                        "evaluationEvidence.evaluatedConditions[0].actualValue", List.of())),
                "This should never survive grounding.", "TEST");

        GroundingResult result = checker.check(noActionsContext, candidate);

        assertNull(result.validated().actionNote());
    }
}
