package com.nexus.rule.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.rule.ai.AiCandidateParser;
import com.nexus.rule.ai.AiExplanationContextMapper;
import com.nexus.rule.ai.DeterministicExplanationRenderer;
import com.nexus.rule.ai.FakeAiReasoningBoundary;
import com.nexus.rule.ai.GroundingChecker;
import com.nexus.rule.ai.RuleMatchExplanation;
import com.nexus.rule.ai.RuleMatchExplanationContextBuilder;
import com.nexus.rule.ai.RuleMatchExplanationService;
import com.nexus.rule.ai.RuleMatchExplanationService.ExplanationOutcome;
import com.nexus.rule.ai.evaluation.ScenarioLibrary.EvaluationScenario;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tier 4 Phase 2D: the main evaluation suite. This is NOT a reimplementation
 * of GroundingChecker/AiCandidateParser/DeterministicExplanationRenderer -
 * every scenario is run through the real, unmodified production
 * RuleMatchExplanationService, exercising the exact same machinery
 * production traffic would use. Only the AiReasoningBoundary is faked
 * (FakeAiReasoningBoundary, already an existing Phase 2A/2B test double),
 * so the whole suite is deterministic, offline, and requires no network,
 * database, or live AI provider.
 *
 * LEVEL 1 (this class) is purely structural: every assertion here checks a
 * mechanically provable fact (does the evidenceRef resolve, does
 * assertedValue match, is the mandatory claim present, is matchId/
 * generatedBy/actionNote NEXUS-owned, did an adversarial output correctly
 * fall back). It does NOT and cannot judge whether AI-generated prose is a
 * faithful, reasonable, or non-misleading paraphrase of that structured
 * data - scenario 22 ("status-physical-implication") exists specifically
 * to demonstrate a case that Level 1 MUST accept (the evidenceRef is valid
 * and the value matches) even though the prose is misleading; catching
 * that requires the human LEVEL 2 review described in
 * src/test/resources/evaluation/semantic-rubric.md. This boundary is
 * deliberate, not a gap to be closed with a keyword/regex/NLP filter.
 */
class RuleMatchExplanationEvaluationTest {

    // DeterministicExplanationRenderer.CAUSAL_UNCERTAINTY/DEVICE_UNKNOWN/
    // ACTION_DISCLAIMER are package-private to com.nexus.rule.ai; this
    // evaluation suite deliberately lives in the com.nexus.rule.ai.evaluation
    // subpackage (per the approved Phase 2D plan) and this phase forbids
    // widening production visibility, so the exact literal text is mirrored
    // here for assertion purposes only - no production behavior is
    // reimplemented, only these three fixed strings are duplicated.
    private static final String CAUSAL_UNCERTAINTY =
            "Whether this observation reflects a genuine physical condition versus a sensor or equipment issue "
            + "cannot be determined from available evidence.";
    private static final String DEVICE_UNKNOWN =
            "The originating device could not be determined because the linked domain event is no longer available.";
    private static final String ACTION_DISCLAIMER =
            "NEXUS recorded the action(s) above as executed by its action pipeline. "
            + "All current action executors are stub implementations and do not perform real external effects; "
            + "this is not evidence that any physical action occurred.";

    private RuleMatchExplanationService buildService(EvaluationScenario scenario) {
        RuleMatchExplanationContextBuilder contextBuilder = mock(RuleMatchExplanationContextBuilder.class);
        when(contextBuilder.build(scenario.context().matchId())).thenReturn(scenario.context());

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        AiExplanationContextMapper mapper = new AiExplanationContextMapper();
        AiCandidateParser parser = new AiCandidateParser(objectMapper);
        GroundingChecker groundingChecker = new GroundingChecker(objectMapper);
        DeterministicExplanationRenderer renderer = new DeterministicExplanationRenderer();

        FakeAiReasoningBoundary boundary = scenario.fakeAiRawResponse()
                .map(FakeAiReasoningBoundary::alwaysReturning)
                .orElseGet(FakeAiReasoningBoundary::alwaysEmpty);

        return new RuleMatchExplanationService(contextBuilder, mapper, boundary, parser, groundingChecker, renderer, scenario.aiEnabled());
    }

    private boolean hasClaimTextContaining(RuleMatchExplanation explanation, String substring) {
        return explanation.claims().stream().anyMatch(c -> c.text() != null && c.text().contains(substring));
    }

    private boolean hasMandatoryCausalUncertainty(RuleMatchExplanation explanation) {
        return explanation.claims().stream()
                .anyMatch(c -> c.text() != null && c.text().equals(CAUSAL_UNCERTAINTY));
    }

    private boolean hasMandatoryDeviceUnknown(RuleMatchExplanation explanation) {
        return explanation.claims().stream()
                .anyMatch(c -> c.text() != null && c.text().equals(DEVICE_UNKNOWN));
    }

    /** Named pass/total counters, printed as the final scorecard - never collapsed into one score. */
    private static final class Scorecard {
        private final Map<String, int[]> dimensions = new LinkedHashMap<>();

        void record(String dimension, boolean pass) {
            int[] counts = dimensions.computeIfAbsent(dimension, d -> new int[2]);
            counts[1]++;
            if (pass) {
                counts[0]++;
            }
        }

        String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n===== Tier 4 Phase 2D — Evaluation Scorecard (Level 1: structural, deterministic) =====\n");
            for (Map.Entry<String, int[]> entry : dimensions.entrySet()) {
                int pass = entry.getValue()[0];
                int total = entry.getValue()[1];
                double pct = total == 0 ? 0.0 : (100.0 * pass / total);
                sb.append(String.format("  %-70s %3d/%3d  (%.1f%%)%n", entry.getKey(), pass, total, pct));
            }
            sb.append("=========================================================================================\n");
            sb.append("NEXUS mechanically validates structured factual values against cited evidence.\n");
            sb.append("NEXUS structurally prevents claims from grounding against nonexistent physical-outcome evidence.\n");
            sb.append("Level 1 does not and cannot prove free-text semantic faithfulness - see semantic-rubric.md for Level 2.\n");
            return sb.toString();
        }
    }

    @Test
    void evaluateAllScenarios() {
        List<EvaluationScenario> scenarios = ScenarioLibrary.allScenarios();
        assertTrue(scenarios.size() >= 18, "expected approximately 18-20+ scenarios, found " + scenarios.size());

        Scorecard scorecard = new Scorecard();
        List<String> failures = new ArrayList<>();

        for (EvaluationScenario scenario : scenarios) {
            evaluateOne(scenario, scorecard, failures);
        }

        System.out.println(scorecard.render());

        assertTrue(failures.isEmpty(), "Phase 2D evaluation failures:\n" + String.join("\n", failures));
    }

    private void evaluateOne(EvaluationScenario scenario, Scorecard scorecard, List<String> failures) {
        RuleMatchExplanationService service = buildService(scenario);
        ExplanationOutcome outcome = service.explainWithOutcome(scenario.context().matchId());
        RuleMatchExplanation explanation = outcome.explanation();
        ScenarioLibrary.EvaluationScenario.ExpectedOutcome expected = scenario.expected();
        String id = scenario.id();

        // ---- generatedBy decision correctness (overall) ----
        boolean generatedByCorrect = expected.expectedGeneratedBy().equals(explanation.generatedBy());
        scorecard.record("AI/fallback decision correctness (overall)", generatedByCorrect);
        if (!generatedByCorrect) {
            failures.add(id + ": expected generatedBy=" + expected.expectedGeneratedBy() + " but was " + explanation.generatedBy());
        }

        // ---- Integrity: match identity correctness ----
        boolean matchIdCorrect = expected.expectedMatchId().equals(explanation.matchId());
        scorecard.record("Integrity: match identity correctness", matchIdCorrect);
        if (!matchIdCorrect) {
            failures.add(id + ": matchId mismatch, expected " + expected.expectedMatchId() + " got " + explanation.matchId());
        }

        // ---- Safety - Structural: mandatory safety claim preservation ----
        boolean causalUncertaintyPresent = hasMandatoryCausalUncertainty(explanation);
        boolean deviceUnknownOk = !expected.expectMandatoryDeviceUnknownClaim() || hasMandatoryDeviceUnknown(explanation);
        boolean mandatoryClaimsOk = causalUncertaintyPresent && deviceUnknownOk;
        scorecard.record("Safety - Structural: mandatory safety claim preservation", mandatoryClaimsOk);
        if (!mandatoryClaimsOk) {
            failures.add(id + ": mandatory safety claim missing (causalUncertaintyPresent=" + causalUncertaintyPresent
                    + ", deviceUnknownOk=" + deviceUnknownOk + ")");
        }

        // ---- Safety - Structural: actionNote ownership ----
        String expectedActionNote = expected.expectActionNoteEnforced() ? ACTION_DISCLAIMER : null;
        boolean actionNoteCorrect = java.util.Objects.equals(expectedActionNote, explanation.actionNote());
        scorecard.record("Safety - Structural: actionNote ownership", actionNoteCorrect);
        if (!actionNoteCorrect) {
            failures.add(id + ": actionNote mismatch, expected " + expectedActionNote + " got " + explanation.actionNote());
        }

        // ---- Grounding: positive scenarios (AI expected to succeed with the claim intact) ----
        boolean isGroundingPositiveScenario = "AI".equals(expected.expectedGeneratedBy());
        if (isGroundingPositiveScenario) {
            boolean claimSurvived = !explanation.claims().isEmpty()
                    && explanation.claims().stream().anyMatch(c -> c.tier() != null);
            scorecard.record("Grounding: evidence-reference resolvability & FACT-value correctness", claimSurvived && generatedByCorrect);
        }

        // ---- Grounding: adversarial scenarios (unsupported/invalid claim rejection) ----
        boolean isAdversarialClaimScenario = id.equals("14-missing-assertedValue") || id.equals("15-wrong-assertedValue")
                || id.equals("16-nonexistent-evidenceref-physical-outcome") || id.equals("17-all-claims-invalid");
        if (isAdversarialClaimScenario) {
            scorecard.record("Grounding: unsupported/invalid claim rejection", generatedByCorrect);
        }

        // ---- Safety - Structural: physical-outcome evidence-reference rejection (Level 1 only) ----
        if (id.equals("16-nonexistent-evidenceref-physical-outcome")) {
            scorecard.record("Safety - Structural: physical-outcome evidence-reference rejection", generatedByCorrect);
        }

        // ---- Reliability: fallback correctness (adversarial/unavailable set) ----
        boolean isFallbackScenario = List.of("12-malformed-json", "13-schema-invalid-output", "14-missing-assertedValue",
                "15-wrong-assertedValue", "16-nonexistent-evidenceref-physical-outcome", "17-all-claims-invalid",
                "18-provider-unavailable").contains(id);
        if (isFallbackScenario) {
            boolean fallbackCorrect = generatedByCorrect && matchIdCorrect && actionNoteCorrect && mandatoryClaimsOk;
            scorecard.record("Reliability: fallback correctness", fallbackCorrect);
        }

        // ---- Reliability: provider-failure containment (deterministic-scorecard indicator only;
        //      the persisted-row-level proof lives in FailureContainmentEvaluationTest) ----
        if (id.equals("18-provider-unavailable")) {
            scorecard.record("Reliability: provider-failure containment (deterministic indicator)", generatedByCorrect);
        }

        // ---- Integrity: historical rule snapshot correctness ----
        if (id.equals("20-historical-rule-snapshot")) {
            boolean snapshotCorrect = hasClaimTextContainingOrSummary(explanation, "Historical Snapshot Rule A");
            scorecard.record("Integrity: historical rule snapshot correctness", snapshotCorrect);
            if (!snapshotCorrect) {
                failures.add(id + ": explanation does not reference the persisted historical rule snapshot name");
            }
        }

        // ---- scenario-specific content spot checks (not separate scorecard dimensions - already
        //      covered by the "AI/fallback decision correctness" and grounding rows above; these are
        //      extra failure-message precision only) ----
        if (id.equals("01-numeric-gt-threshold") && !hasClaimTextContaining(explanation, "34.7")) {
            failures.add(id + ": expected explanation to state the observed value 34.7");
        }
        if (id.equals("21-supported-inference")) {
            boolean hasInference = explanation.claims().stream().anyMatch(c -> c.tier() == com.nexus.rule.ai.ClaimTier.INFERENCE);
            if (!hasInference) {
                failures.add(id + ": expected a surviving INFERENCE claim");
            }
        }
        if (id.equals("22-status-physical-implication")) {
            // Deliberately NOT a failure: this claim is structurally valid and MUST survive Level 1.
            boolean survivedAsExpected = "AI".equals(explanation.generatedBy())
                    && hasClaimTextContaining(explanation, "The motor turned on.");
            scorecard.record("Grounding: evidence-reference resolvability & FACT-value correctness (semantic-review boundary case)", survivedAsExpected);
        }
    }

    private boolean hasClaimTextContainingOrSummary(RuleMatchExplanation explanation, String substring) {
        if (explanation.summary() != null && explanation.summary().contains(substring)) {
            return true;
        }
        return hasClaimTextContaining(explanation, substring);
    }
}
