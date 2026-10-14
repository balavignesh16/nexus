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
        return new RuleMatchExplanation(context.matchId(), "summary", List.of(claims), null, "AI");
    }

    private Claim groundedFact() {
        return new Claim(ClaimTier.FACT, "Observed value was 34.7.",
                "evaluationEvidence.evaluatedConditions[0].actualValue", 34.7, List.of());
    }

    // ---------- FACT: structural value verification ----------

    @Test
    void validFact_correctAssertedValue_accepted() {
        GroundingResult result = checker.check(context, explanationWith(groundedFact()));

        assertEquals(1, result.validated().claims().stream().filter(c -> c.tier() == ClaimTier.FACT).count());
        assertEquals(0, result.rejectedClaimCount());
    }

    @Test
    void fact_numericStringAssertedValue_matchesDoubleEvidence_accepted() {
        Claim fact = new Claim(ClaimTier.FACT, "Observed value was 34.7.",
                "evaluationEvidence.evaluatedConditions[0].actualValue", "34.7", List.of());

        GroundingResult result = checker.check(context, explanationWith(fact));

        assertEquals(0, result.rejectedClaimCount());
    }

    @Test
    void fact_wrongAssertedValue_rejected() {
        // The exact scenario this mechanism exists to catch: citing real
        // evidence (actualValue = 34.7) but asserting a different value.
        Claim fact = new Claim(ClaimTier.FACT, "Observed value was 40.2.",
                "evaluationEvidence.evaluatedConditions[0].actualValue", 40.2, List.of());

        GroundingResult result = checker.check(context, explanationWith(fact));

        assertTrue(result.validated().claims().stream().noneMatch(c -> c.tier() == ClaimTier.FACT));
        assertEquals(1, result.rejectedClaimCount());
        assertTrue(result.rejectionReasons().stream().anyMatch(r -> r.contains("assertedValue does not match")));
    }

    @Test
    void fact_correctBooleanAssertedValue_accepted() {
        Claim fact = new Claim(ClaimTier.FACT, "The condition was met.",
                "evaluationEvidence.evaluatedConditions[0].conditionMet", true, List.of());

        GroundingResult result = checker.check(context, explanationWith(fact));

        assertEquals(0, result.rejectedClaimCount());
    }

    @Test
    void fact_incorrectBooleanAssertedValue_rejected() {
        Claim fact = new Claim(ClaimTier.FACT, "The condition was not met.",
                "evaluationEvidence.evaluatedConditions[0].conditionMet", false, List.of());

        GroundingResult result = checker.check(context, explanationWith(fact));

        assertEquals(1, result.rejectedClaimCount());
    }

    @Test
    void fact_explicitNullAssertion_acceptedWhenEvidenceResolvesNull() {
        // Build a context whose evidence genuinely resolves to null at a path.
        Map<String, Object> evidence = new java.util.HashMap<>();
        evidence.put("outcome", "not_matched");
        Map<String, Object> condition = new java.util.HashMap<>();
        condition.put("field", "VALUE");
        condition.put("operator", "GT");
        condition.put("expectedValue", "30.0");
        condition.put("actualValue", null);
        condition.put("conditionMet", false);
        evidence.put("evaluatedConditions", List.of(condition));

        RuleMatchExplanationContext nullValueContext = new RuleMatchExplanationContext(
                context.matchId(), context.ruleId(), context.ruleSnapshot(), context.eventId(), context.correlationId(),
                evidence, context.matchedAt(), context.message(), context.deviceId(), context.domainEvent(), context.actions());

        Claim fact = new Claim(ClaimTier.FACT, "No value was observed.",
                "evaluationEvidence.evaluatedConditions[0].actualValue", null, List.of());

        GroundingResult result = checker.check(nullValueContext, new RuleMatchExplanation(
                nullValueContext.matchId(), "summary", List.of(fact), null, "AI"));

        assertEquals(0, result.rejectedClaimCount());
    }

    @Test
    void fact_nonExistentPhysicalOutcomeField_rejected() {
        // There is no "physicalOutcome" field anywhere in the context - a
        // FACT claim attempting to cite one is structurally rejected, with
        // no keyword/phrase matching involved.
        Claim fact = new Claim(ClaimTier.FACT, "The actuator physically moved.",
                "actions[0].physicalOutcome", "MOVED", List.of());

        GroundingResult result = checker.check(context, explanationWith(fact));

        assertEquals(1, result.rejectedClaimCount());
        assertTrue(result.rejectionReasons().stream().anyMatch(r -> r.contains("does not resolve")));
    }

    @Test
    void factMissingEvidenceRef_rejected() {
        Claim fact = new Claim(ClaimTier.FACT, "Observed value was 34.7.", null, 34.7, List.of());

        GroundingResult result = checker.check(context, explanationWith(fact));

        assertTrue(result.validated().claims().isEmpty());
        assertEquals(1, result.rejectedClaimCount());
    }

    @Test
    void factInvalidEvidenceRef_rejected() {
        Claim fact = new Claim(ClaimTier.FACT, "Observed value was 999.", "evaluationEvidence.nonExistentField", 999, List.of());

        GroundingResult result = checker.check(context, explanationWith(fact));

        assertTrue(result.validated().claims().isEmpty());
        assertEquals(1, result.rejectedClaimCount());
    }

    // ---------- UNKNOWN ----------

    @Test
    void unknownWithoutEvidence_accepted() {
        Claim unknown = new Claim(ClaimTier.UNKNOWN, "Cannot be determined.", null, null, List.of());

        GroundingResult result = checker.check(context, explanationWith(unknown));

        assertEquals(2, result.validated().claims().size(),
                "the candidate's own UNKNOWN claim plus the mandatory platform CAUSAL_UNCERTAINTY claim");
    }

    @Test
    void unknownWithEvidenceRef_rejected() {
        Claim unknown = new Claim(ClaimTier.UNKNOWN, "Cannot be determined.", "matchedAt", null, List.of());

        GroundingResult result = checker.check(context, explanationWith(unknown));

        assertEquals(1, result.rejectedClaimCount());
    }

    @Test
    void unknownWithAssertedValue_rejected() {
        Claim unknown = new Claim(ClaimTier.UNKNOWN, "Cannot be determined.", null, "something", List.of());

        GroundingResult result = checker.check(context, explanationWith(unknown));

        assertEquals(1, result.rejectedClaimCount());
    }

    @Test
    void unknownWithSupportingRefs_rejected() {
        Claim unknown = new Claim(ClaimTier.UNKNOWN, "Cannot be determined.", null, null, List.of("matchedAt"));

        GroundingResult result = checker.check(context, explanationWith(unknown));

        assertEquals(1, result.rejectedClaimCount());
    }

    // ---------- INFERENCE ----------

    @Test
    void validInference_accepted() {
        Claim inference = new Claim(ClaimTier.INFERENCE,
                "The threshold violation likely triggered the recorded action.",
                null, null, List.of("evaluationEvidence.evaluatedConditions[0].actualValue", "actions[0].status"));

        GroundingResult result = checker.check(context, explanationWith(inference));

        assertEquals(0, result.rejectedClaimCount());
    }

    @Test
    void inferenceWithEvidenceRef_rejected() {
        Claim inference = new Claim(ClaimTier.INFERENCE, "Likely cause.", "matchedAt", null,
                List.of("evaluationEvidence.evaluatedConditions[0].actualValue"));

        GroundingResult result = checker.check(context, explanationWith(inference));

        assertEquals(1, result.rejectedClaimCount());
    }

    @Test
    void inferenceWithAssertedValue_rejected() {
        Claim inference = new Claim(ClaimTier.INFERENCE, "Likely cause.", null, 42,
                List.of("evaluationEvidence.evaluatedConditions[0].actualValue"));

        GroundingResult result = checker.check(context, explanationWith(inference));

        assertEquals(1, result.rejectedClaimCount());
    }

    @Test
    void inferenceWithEmptySupportingRefs_rejected() {
        Claim inference = new Claim(ClaimTier.INFERENCE, "Likely cause.", null, null, List.of());

        GroundingResult result = checker.check(context, explanationWith(inference));

        assertEquals(1, result.rejectedClaimCount());
    }

    @Test
    void inferenceWithInvalidSupportingRef_rejected() {
        Claim inference = new Claim(ClaimTier.INFERENCE, "Likely cause.", null, null, List.of("nonExistent.path"));

        GroundingResult result = checker.check(context, explanationWith(inference));

        assertEquals(1, result.rejectedClaimCount());
    }

    // ---------- Candidate-level shape ----------

    @Test
    void matchIdMismatch_rejectedEntirely() {
        RuleMatchExplanation wrongMatch = new RuleMatchExplanation(
                UUID.randomUUID(), "summary",
                List.of(groundedFact()),
                null, "AI");

        GroundingResult result = checker.check(context, wrongMatch);

        assertTrue(result.validated().claims().isEmpty());
        assertEquals(context.matchId(), result.validated().matchId());
        assertEquals(1, result.rejectedClaimCount());
    }

    @Test
    void blankClaimText_rejected() {
        Claim blank = new Claim(ClaimTier.FACT, "   ", "matchedAt", "x", List.of());

        GroundingResult result = checker.check(context, explanationWith(blank));

        assertTrue(result.validated().claims().isEmpty());
    }

    @Test
    void emptyClaims_flaggedAsInvalid() {
        RuleMatchExplanation empty = new RuleMatchExplanation(context.matchId(), "summary", List.of(), null, "AI");

        GroundingResult result = checker.check(context, empty);

        assertTrue(result.rejectionReasons().stream().anyMatch(r -> r.contains("non-empty")));
    }

    @Test
    void allClaimsRejected_signalsFallbackRequired() {
        Claim badFact = new Claim(ClaimTier.FACT, "Wrong value.", "nonExistent.path", "x", List.of());
        Claim badUnknown = new Claim(ClaimTier.UNKNOWN, "x", "matchedAt", null, List.of());

        GroundingResult result = checker.check(context, explanationWith(badFact, badUnknown));

        assertTrue(result.validated().claims().isEmpty(),
                "an empty validated claims list is the caller's signal that the deterministic fallback is required");
        assertEquals(2, result.rejectedClaimCount());
    }

    // ---------- Issue 1 (prior round): null supportingEvidenceRefs must be rejected, not silently normalized ----------

    @Test
    void factWithNullSupportingEvidenceRefs_rejected_noException() {
        Claim fact = new Claim(ClaimTier.FACT, "Observed value was 34.7.",
                "evaluationEvidence.evaluatedConditions[0].actualValue", 34.7, null);

        GroundingResult result = checker.check(context, explanationWith(fact));

        assertTrue(result.validated().claims().isEmpty());
        assertEquals(1, result.rejectedClaimCount());
        assertTrue(result.rejectionReasons().stream().anyMatch(r -> r.contains("supportingEvidenceRefs")));
    }

    @Test
    void unknownWithNullSupportingEvidenceRefs_rejected_noException() {
        Claim unknown = new Claim(ClaimTier.UNKNOWN, "Cannot be determined.", null, null, null);

        GroundingResult result = checker.check(context, explanationWith(unknown));

        assertTrue(result.validated().claims().isEmpty());
        assertEquals(1, result.rejectedClaimCount());
    }

    @Test
    void inferenceWithNullSupportingEvidenceRefs_rejected_noException() {
        Claim inference = new Claim(ClaimTier.INFERENCE, "Likely cause.", null, null, null);

        GroundingResult result = checker.check(context, explanationWith(inference));

        assertTrue(result.validated().claims().isEmpty());
        assertEquals(1, result.rejectedClaimCount());
    }

    // ---------- Issue 2 (prior round): actionNote is enforced by GroundingChecker, never trusted from the candidate ----------

    private RuleMatchExplanationContext contextWithNoActions() {
        return new RuleMatchExplanationContext(
                context.matchId(), context.ruleId(), context.ruleSnapshot(), context.eventId(), context.correlationId(),
                context.evaluationEvidence(), context.matchedAt(), context.message(),
                context.deviceId(), context.domainEvent(), List.of());
    }

    @Test
    void correctActionNote_actionsExist_validatedOutputHasExactDisclaimer() {
        RuleMatchExplanation candidate = new RuleMatchExplanation(
                context.matchId(), "summary", List.of(groundedFact()),
                ExecutionTruthfulness.noteFor(ExecutionTruthfulness.typesOf(context)), "AI");

        GroundingResult result = checker.check(context, candidate);

        assertEquals(ExecutionTruthfulness.noteFor(ExecutionTruthfulness.typesOf(context)), result.validated().actionNote());
    }

    @Test
    void nullActionNote_actionsExist_validatedOutputStillHasExactDisclaimer() {
        RuleMatchExplanation candidate = new RuleMatchExplanation(
                context.matchId(), "summary", List.of(groundedFact()), null, "AI");

        GroundingResult result = checker.check(context, candidate);

        assertEquals(ExecutionTruthfulness.noteFor(ExecutionTruthfulness.typesOf(context)), result.validated().actionNote());
    }

    @Test
    void maliciousActionNote_actionsExist_validatedOutputReplacesItWithExactDisclaimer() {
        RuleMatchExplanation candidate = new RuleMatchExplanation(
                context.matchId(), "summary", List.of(groundedFact()),
                "The system physically actuated the device and confirmed success.", "AI");

        GroundingResult result = checker.check(context, candidate);

        assertEquals(ExecutionTruthfulness.noteFor(ExecutionTruthfulness.typesOf(context)), result.validated().actionNote());
        assertTrue(result.validated().actionNote() != null
                && !result.validated().actionNote().contains("physically actuated"));
    }

    @Test
    void actionsEmpty_candidateContainsActionNote_validatedOutputActionNoteIsNull() {
        RuleMatchExplanationContext noActionsContext = contextWithNoActions();
        RuleMatchExplanation candidate = new RuleMatchExplanation(
                noActionsContext.matchId(), "summary",
                List.of(new Claim(ClaimTier.FACT, "Observed value was 34.7.",
                        "evaluationEvidence.evaluatedConditions[0].actualValue", 34.7, List.of())),
                "This should never survive grounding.", "AI");

        GroundingResult result = checker.check(noActionsContext, candidate);

        assertNull(result.validated().actionNote());
    }

    // ---------- Issue 3 (this round): mandatory platform-owned UNKNOWN safety claims ----------

    @Test
    void mandatoryCausalUncertainty_alwaysAppendedWhenClaimsSurvive() {
        GroundingResult result = checker.check(context, explanationWith(groundedFact()));

        assertTrue(result.validated().claims().stream().anyMatch(c ->
                c.tier() == ClaimTier.UNKNOWN && c.text().equals(DeterministicExplanationRenderer.CAUSAL_UNCERTAINTY)));
    }

    @Test
    void mandatoryDeviceUnknown_appendedOnlyWhenDeviceIdIsNull() {
        RuleMatchExplanationContext noDeviceContext = new RuleMatchExplanationContext(
                context.matchId(), context.ruleId(), context.ruleSnapshot(), context.eventId(), context.correlationId(),
                context.evaluationEvidence(), context.matchedAt(), context.message(),
                null, null, context.actions());
        Claim fact = new Claim(ClaimTier.FACT, "Observed value was 34.7.",
                "evaluationEvidence.evaluatedConditions[0].actualValue", 34.7, List.of());
        RuleMatchExplanation candidate = new RuleMatchExplanation(noDeviceContext.matchId(), "summary", List.of(fact), null, "AI");

        GroundingResult result = checker.check(noDeviceContext, candidate);

        assertTrue(result.validated().claims().stream().anyMatch(c ->
                c.tier() == ClaimTier.UNKNOWN && c.text().equals(DeterministicExplanationRenderer.DEVICE_UNKNOWN)));
    }

    @Test
    void mandatoryDeviceUnknown_notAppendedWhenDeviceIdIsPresent() {
        GroundingResult result = checker.check(context, explanationWith(groundedFact()));

        assertTrue(result.validated().claims().stream().noneMatch(c ->
                c.text().equals(DeterministicExplanationRenderer.DEVICE_UNKNOWN)));
    }

    @Test
    void candidatesOwnDuplicateUnknown_doesNotSuppressPlatformMandatoryClaim() {
        Claim ownUnknown = new Claim(ClaimTier.UNKNOWN,
                "It is unclear whether this was a real physical event.", null, null, List.of());

        GroundingResult result = checker.check(context, explanationWith(groundedFact(), ownUnknown));

        long causalUncertaintyCount = result.validated().claims().stream()
                .filter(c -> c.text().equals(DeterministicExplanationRenderer.CAUSAL_UNCERTAINTY))
                .count();
        assertEquals(1, causalUncertaintyCount, "the platform claim must still be present regardless of the candidate's own similar claim");
        assertTrue(result.validated().claims().stream().anyMatch(c -> c.text().equals(ownUnknown.text())),
                "the candidate's own claim is not suppressed either - both coexist");
    }

    @Test
    void zeroSurvivingClaims_noMandatoryClaimsAppendedByChecker() {
        Claim badFact = new Claim(ClaimTier.FACT, "Wrong.", "nonExistent.path", "x", List.of());

        GroundingResult result = checker.check(context, explanationWith(badFact));

        assertTrue(result.validated().claims().isEmpty(),
                "when nothing survives, the checker appends nothing either - the caller must use the full deterministic fallback, "
                        + "which already includes the mandatory claims on its own");
    }

    // ---------- ruleId identity cannot drift (general FACT mechanism, no dedicated field needed) ----------

    @Test
    void factCitingRuleIdInSnapshot_wrongAssertedValue_rejected() {
        // ruleSnapshot as persisted contains its own "ruleId" key (the full
        // historical Rule conversion) - even though the AI-facing DTO never
        // exposes it (AiExplanationContextMapper strips it), GroundingChecker
        // validates against the FULL internal context, so any claim that
        // attempts to assert an identity value is still caught by the same
        // general FACT mechanism, with no dedicated "ruleId check" needed.
        Map<String, Object> snapshotWithRuleId = new java.util.HashMap<>(context.ruleSnapshot());
        snapshotWithRuleId.put("ruleId", context.ruleId().toString());
        RuleMatchExplanationContext contextWithRuleId = new RuleMatchExplanationContext(
                context.matchId(), context.ruleId(), snapshotWithRuleId, context.eventId(), context.correlationId(),
                context.evaluationEvidence(), context.matchedAt(), context.message(),
                context.deviceId(), context.domainEvent(), context.actions());

        Claim wrongRuleId = new Claim(ClaimTier.FACT, "Rule ID is something else.",
                "ruleSnapshot.ruleId", UUID.randomUUID().toString(), List.of());

        GroundingResult result = checker.check(contextWithRuleId, new RuleMatchExplanation(
                contextWithRuleId.matchId(), "summary", List.of(wrongRuleId), null, "AI"));

        assertEquals(1, result.rejectedClaimCount());
    }
}
