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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests RuleMatchExplanationService - the production orchestration
 * boundary (Tier 4 Phase 2B) - using a mocked RuleMatchExplanationContextBuilder
 * (no database) and the FakeAiReasoningBoundary pattern already
 * established in Phase 2A. Every Phase 2A safety guarantee is re-proven
 * through this new service layer, not just at the underlying component
 * level.
 */
class RuleMatchExplanationServiceTest {

    private RuleMatchExplanationContextBuilder contextBuilder;
    private AiExplanationContextMapper mapper;
    private AiCandidateParser parser;
    private GroundingChecker groundingChecker;
    private DeterministicExplanationRenderer renderer;
    private UUID matchId;
    private RuleMatchExplanationContext context;

    @BeforeEach
    void setUp() {
        contextBuilder = mock(RuleMatchExplanationContextBuilder.class);
        mapper = new AiExplanationContextMapper();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        parser = new AiCandidateParser(objectMapper);
        groundingChecker = new GroundingChecker(objectMapper);
        renderer = new DeterministicExplanationRenderer();

        matchId = UUID.randomUUID();
        context = new RuleMatchExplanationContext(
                matchId, UUID.randomUUID(),
                Map.of("name", "High Temperature Alert",
                        "conditions", List.of(Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0"))),
                UUID.randomUUID(), "corr-1",
                Map.of("outcome", "matched", "evaluatedConditions", List.of(
                        Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0",
                                "actualValue", 34.7, "conditionMet", true))),
                Instant.now(), "All conditions met",
                UUID.randomUUID(), null,
                List.of(new ActionSummary(UUID.randomUUID(), "CREATE_ALERT", "SUCCESS", Instant.now(), "ok")));

        when(contextBuilder.build(matchId)).thenReturn(context);
    }

    private RuleMatchExplanationService service(AiReasoningBoundary boundary, boolean aiEnabled) {
        return new RuleMatchExplanationService(contextBuilder, mapper, boundary, parser, groundingChecker, renderer, aiEnabled);
    }

    private String candidateJson(String claimJson) {
        return """
                {"matchId":"00000000-0000-0000-0000-000000000000","summary":"s","claims":[%s]}
                """.formatted(claimJson);
    }

    private String validFactClaimJson() {
        return "{\"tier\":\"FACT\",\"text\":\"Observed value was 34.7.\","
                + "\"evidenceRef\":\"evaluationEvidence.evaluatedConditions[0].actualValue\","
                + "\"assertedValue\":34.7,\"supportingEvidenceRefs\":[]}";
    }

    // ---------- 1: AI disabled ----------

    @Test
    void aiDisabled_returnsDeterministicRendererOutput() {
        RuleMatchExplanationService service = service(FakeAiReasoningBoundary.alwaysReturning(candidateJson(validFactClaimJson())), false);

        RuleMatchExplanation result = service.explain(matchId);

        assertEquals("DETERMINISTIC_FALLBACK", result.generatedBy());
    }

    // ---------- 2: provider returns nothing ----------

    @Test
    void providerReturnsEmpty_fallsBackToDeterministic() {
        RuleMatchExplanationService service = service(FakeAiReasoningBoundary.alwaysEmpty(), true);

        RuleMatchExplanation result = service.explain(matchId);

        assertEquals("DETERMINISTIC_FALLBACK", result.generatedBy());
    }

    // ---------- 3: malformed JSON ----------

    @Test
    void malformedJson_fallsBackToDeterministic() {
        RuleMatchExplanationService service = service(FakeAiReasoningBoundary.alwaysReturning("{ not valid json ]]"), true);

        RuleMatchExplanation result = service.explain(matchId);

        assertEquals("DETERMINISTIC_FALLBACK", result.generatedBy());
    }

    // ---------- 4: schema-invalid response ----------

    @Test
    void schemaInvalidResponse_fallsBackToDeterministic() {
        // Missing required "claims" array entirely.
        RuleMatchExplanationService service = service(
                FakeAiReasoningBoundary.alwaysReturning("{\"matchId\":\"00000000-0000-0000-0000-000000000000\",\"summary\":\"s\"}"), true);

        RuleMatchExplanation result = service.explain(matchId);

        assertEquals("DETERMINISTIC_FALLBACK", result.generatedBy());
    }

    // ---------- 5: correct candidate ----------

    @Test
    void correctCandidate_validatedAiExplanation() {
        RuleMatchExplanationService service = service(FakeAiReasoningBoundary.alwaysReturning(candidateJson(validFactClaimJson())), true);

        RuleMatchExplanation result = service.explain(matchId);

        assertEquals("AI", result.generatedBy());
        assertEquals(matchId, result.matchId(), "the real matchId must be reattached, not whatever placeholder the model emitted");
        assertTrue(result.claims().stream().anyMatch(c -> c.text().contains("34.7")));
    }

    // ---------- 6: wrong assertedValue ----------

    @Test
    void wrongAssertedValue_fallsBackToDeterministic() {
        String wrongClaim = "{\"tier\":\"FACT\",\"text\":\"Observed value was 40.2.\","
                + "\"evidenceRef\":\"evaluationEvidence.evaluatedConditions[0].actualValue\","
                + "\"assertedValue\":40.2,\"supportingEvidenceRefs\":[]}";
        RuleMatchExplanationService service = service(FakeAiReasoningBoundary.alwaysReturning(candidateJson(wrongClaim)), true);

        RuleMatchExplanation result = service.explain(matchId);

        assertEquals("DETERMINISTIC_FALLBACK", result.generatedBy());
    }

    // ---------- 7: physical-outcome claim ----------

    @Test
    void physicalOutcomeClaim_rejected_fallsBack() {
        String claim = "{\"tier\":\"FACT\",\"text\":\"The actuator physically moved.\","
                + "\"evidenceRef\":\"actions[0].physicalOutcome\","
                + "\"assertedValue\":\"MOVED\",\"supportingEvidenceRefs\":[]}";
        RuleMatchExplanationService service = service(FakeAiReasoningBoundary.alwaysReturning(candidateJson(claim)), true);

        RuleMatchExplanation result = service.explain(matchId);

        assertEquals("DETERMINISTIC_FALLBACK", result.generatedBy());
    }

    // ---------- 8: malicious actionNote ignored ----------

    @Test
    void maliciousActionNote_ignored() {
        String rawJson = """
                {"matchId":"00000000-0000-0000-0000-000000000000","summary":"s",
                 "actionNote":"The system physically actuated the device.","claims":[%s]}
                """.formatted(validFactClaimJson());
        RuleMatchExplanationService service = service(FakeAiReasoningBoundary.alwaysReturning(rawJson), true);

        RuleMatchExplanation result = service.explain(matchId);

        assertEquals(DeterministicExplanationRenderer.ACTION_DISCLAIMER, result.actionNote());
    }

    // ---------- 9: mandatory UNKNOWN claims remain present ----------

    @Test
    void mandatoryUnknownClaims_remainPresent_forSuccessfulAiExplanation() {
        RuleMatchExplanationService service = service(FakeAiReasoningBoundary.alwaysReturning(candidateJson(validFactClaimJson())), true);

        RuleMatchExplanation result = service.explain(matchId);

        assertTrue(result.claims().stream().anyMatch(c -> c.text().equals(DeterministicExplanationRenderer.CAUSAL_UNCERTAINTY)));
    }

    // ---------- 10: provider exception cannot break the service ----------

    @Test
    void providerException_doesNotEscapeService_fallsBack() {
        AiReasoningBoundary throwingBoundary = ctx -> {
            throw new RuntimeException("simulated provider failure");
        };
        RuleMatchExplanationService service = service(throwingBoundary, true);

        RuleMatchExplanation result = service.explain(matchId);

        assertEquals("DETERMINISTIC_FALLBACK", result.generatedBy());
    }

    // ---------- 11: missing API key does not prevent construction/use ----------

    @Test
    void serviceUsableWithoutRealProviderConfigured() {
        // A NoOpAiReasoningBoundary-equivalent fake stands in for "no
        // provider configured" - the service must still function, falling
        // back cleanly, exactly as it would with a real, disabled/no-op
        // production bean.
        RuleMatchExplanationService service = service(FakeAiReasoningBoundary.alwaysEmpty(), true);

        RuleMatchExplanation result = service.explain(matchId);

        assertNotNull(result);
        assertEquals("DETERMINISTIC_FALLBACK", result.generatedBy());
    }
}
