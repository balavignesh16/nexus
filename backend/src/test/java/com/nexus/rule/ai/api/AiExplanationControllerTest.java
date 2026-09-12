package com.nexus.rule.ai.api;

import com.nexus.rule.ai.AiExplanationMetrics;
import com.nexus.rule.ai.Claim;
import com.nexus.rule.ai.ClaimTier;
import com.nexus.rule.ai.ExplanationRateLimiter;
import com.nexus.rule.ai.RateLimitExceededException;
import com.nexus.rule.ai.RuleMatchExplanation;
import com.nexus.rule.ai.RuleMatchExplanationService;
import com.nexus.rule.ai.RuleMatchExplanationService.ExplanationOutcome;
import com.nexus.rule.ai.api.dto.RuleMatchExplanationResponse;
import com.nexus.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AiExplanationController (Tier 4 Phase 2C), following the
 * same direct-instantiation-with-mocks convention already established by
 * RuleControllerTest - no Spring context. RuleMatchExplanationService,
 * ExplanationRateLimiter and AiExplanationMetrics are all mocked; only the
 * controller's own thin logic (rate-limit gate ordering, exception
 * propagation, DTO mapping, metrics calls) is under test here.
 */
class AiExplanationControllerTest {

    private RuleMatchExplanationService explanationService;
    private ExplanationRateLimiter rateLimiter;
    private AiExplanationMetrics metrics;
    private AiExplanationController controller;

    @BeforeEach
    void setUp() {
        explanationService = mock(RuleMatchExplanationService.class);
        rateLimiter = mock(ExplanationRateLimiter.class);
        metrics = mock(AiExplanationMetrics.class);
        controller = new AiExplanationController(explanationService, rateLimiter, metrics);
    }

    private MockHttpServletRequest requestFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private RuleMatchExplanation aiExplanation(UUID matchId) {
        List<Claim> claims = List.of(new Claim(ClaimTier.FACT, "Observed value was 34.7.",
                "evaluationEvidence.evaluatedConditions[0].actualValue", 34.7, List.of()));
        return new RuleMatchExplanation(matchId, "summary", claims, null, "AI");
    }

    private RuleMatchExplanation fallbackExplanation(UUID matchId) {
        List<Claim> claims = List.of(new Claim(ClaimTier.UNKNOWN,
                "Whether this observation reflects a genuine physical condition versus a sensor or equipment issue cannot be determined from available evidence.",
                null, null, List.of()));
        return new RuleMatchExplanation(matchId, "fallback summary", claims, null, "DETERMINISTIC_FALLBACK");
    }

    // ---------- 1: AI success ----------

    @Test
    void knownMatch_aiSuccess_returns200WithAiGeneratedBy() {
        UUID matchId = UUID.randomUUID();
        RuleMatchExplanation explanation = aiExplanation(matchId);
        when(explanationService.explainWithOutcome(matchId))
                .thenReturn(new ExplanationOutcome(explanation, RuleMatchExplanationService.REASON_AI_SUCCESS));

        ResponseEntity<RuleMatchExplanationResponse> response = controller.explainRuleMatch(matchId, requestFrom("1.2.3.4"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("AI", response.getBody().generatedBy());
        assertEquals(matchId, response.getBody().matchId());
        verify(metrics).recordOutcome(eq(RuleMatchExplanationService.REASON_AI_SUCCESS), anyLong());
    }

    // ---------- 2: AI disabled ----------

    @Test
    void aiDisabled_returns200WithDeterministicFallback() {
        UUID matchId = UUID.randomUUID();
        when(explanationService.explainWithOutcome(matchId))
                .thenReturn(new ExplanationOutcome(fallbackExplanation(matchId), RuleMatchExplanationService.REASON_AI_DISABLED));

        ResponseEntity<RuleMatchExplanationResponse> response = controller.explainRuleMatch(matchId, requestFrom("1.2.3.4"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("DETERMINISTIC_FALLBACK", response.getBody().generatedBy());
        verify(metrics).recordOutcome(eq(RuleMatchExplanationService.REASON_AI_DISABLED), anyLong());
    }

    // ---------- 3: provider unavailable ----------

    @Test
    void providerUnavailable_returns200WithDeterministicFallback() {
        UUID matchId = UUID.randomUUID();
        when(explanationService.explainWithOutcome(matchId))
                .thenReturn(new ExplanationOutcome(fallbackExplanation(matchId), RuleMatchExplanationService.REASON_AI_UNAVAILABLE));

        ResponseEntity<RuleMatchExplanationResponse> response = controller.explainRuleMatch(matchId, requestFrom("1.2.3.4"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("DETERMINISTIC_FALLBACK", response.getBody().generatedBy());
        verify(metrics).recordOutcome(eq(RuleMatchExplanationService.REASON_AI_UNAVAILABLE), anyLong());
    }

    // ---------- 4: parser failure ----------

    @Test
    void parserFailure_returns200WithDeterministicFallback() {
        UUID matchId = UUID.randomUUID();
        when(explanationService.explainWithOutcome(matchId))
                .thenReturn(new ExplanationOutcome(fallbackExplanation(matchId), RuleMatchExplanationService.REASON_AI_PARSE_FAILURE));

        ResponseEntity<RuleMatchExplanationResponse> response = controller.explainRuleMatch(matchId, requestFrom("1.2.3.4"));

        assertEquals(200, response.getStatusCode().value());
        verify(metrics).recordOutcome(eq(RuleMatchExplanationService.REASON_AI_PARSE_FAILURE), anyLong());
    }

    // ---------- 5: grounding failure ----------

    @Test
    void groundingFailure_returns200WithDeterministicFallback() {
        UUID matchId = UUID.randomUUID();
        when(explanationService.explainWithOutcome(matchId))
                .thenReturn(new ExplanationOutcome(fallbackExplanation(matchId), RuleMatchExplanationService.REASON_AI_GROUNDING_REJECTION));

        ResponseEntity<RuleMatchExplanationResponse> response = controller.explainRuleMatch(matchId, requestFrom("1.2.3.4"));

        assertEquals(200, response.getStatusCode().value());
        verify(metrics).recordOutcome(eq(RuleMatchExplanationService.REASON_AI_GROUNDING_REJECTION), anyLong());
    }

    // ---------- 6: unknown match ----------

    @Test
    void unknownMatch_resourceNotFoundExceptionPropagates_notConvertedToFallback() {
        UUID matchId = UUID.randomUUID();
        when(explanationService.explainWithOutcome(matchId))
                .thenThrow(new ResourceNotFoundException("RuleMatch", matchId.toString()));

        assertThrows(ResourceNotFoundException.class, () -> controller.explainRuleMatch(matchId, requestFrom("1.2.3.4")));
        verify(metrics, never()).recordOutcome(anyString(), anyLong());
    }

    // ---------- 7: rate limited ----------

    @Test
    void rateLimited_throwsAndNeverCallsExplanationService() {
        UUID matchId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new RateLimitExceededException("limit exceeded", 5))
                .when(rateLimiter).acquire("1.2.3.4");

        assertThrows(RateLimitExceededException.class, () -> controller.explainRuleMatch(matchId, requestFrom("1.2.3.4")));

        verify(explanationService, never()).explainWithOutcome(any(UUID.class));
        verify(metrics).recordRateLimited();
        verify(metrics, never()).recordOutcome(anyString(), anyLong());
    }

    private static UUID any(Class<UUID> type) {
        return org.mockito.ArgumentMatchers.any(type);
    }

    // ---------- 8/9: response shape / matchId ownership ----------

    @Test
    void responseMatchId_comesFromServiceResult_notFromRequestAlone() {
        UUID matchId = UUID.randomUUID();
        when(explanationService.explainWithOutcome(matchId))
                .thenReturn(new ExplanationOutcome(aiExplanation(matchId), RuleMatchExplanationService.REASON_AI_SUCCESS));

        ResponseEntity<RuleMatchExplanationResponse> response = controller.explainRuleMatch(matchId, requestFrom("1.2.3.4"));

        assertEquals(matchId, response.getBody().matchId());
    }

    // ---------- 10/11: generatedBy / actionNote passthrough (not overridable) ----------

    @Test
    void generatedByAndActionNote_passThroughUnmodified() {
        UUID matchId = UUID.randomUUID();
        RuleMatchExplanation explanation = new RuleMatchExplanation(
                matchId, "s", List.of(), "NEXUS recorded the action(s) above as executed by its action pipeline.", "DETERMINISTIC_FALLBACK");
        when(explanationService.explainWithOutcome(matchId))
                .thenReturn(new ExplanationOutcome(explanation, RuleMatchExplanationService.REASON_AI_GROUNDING_REJECTION));

        ResponseEntity<RuleMatchExplanationResponse> response = controller.explainRuleMatch(matchId, requestFrom("1.2.3.4"));

        assertEquals("DETERMINISTIC_FALLBACK", response.getBody().generatedBy());
        assertEquals("NEXUS recorded the action(s) above as executed by its action pipeline.", response.getBody().actionNote());
    }

    // ---------- 12: mandatory UNKNOWN claims survive to response ----------

    @Test
    void mandatoryUnknownClaim_survivesToResponse() {
        UUID matchId = UUID.randomUUID();
        RuleMatchExplanation explanation = fallbackExplanation(matchId);
        when(explanationService.explainWithOutcome(matchId))
                .thenReturn(new ExplanationOutcome(explanation, RuleMatchExplanationService.REASON_AI_DISABLED));

        ResponseEntity<RuleMatchExplanationResponse> response = controller.explainRuleMatch(matchId, requestFrom("1.2.3.4"));

        assertEquals(1, response.getBody().claims().size());
        assertEquals(ClaimTier.UNKNOWN, response.getBody().claims().get(0).tier());
    }

    // ---------- 13: unexpected service failure is not swallowed into a fake 200 ----------

    @Test
    void unexpectedServiceException_propagates_notConvertedToFallback200() {
        UUID matchId = UUID.randomUUID();
        when(explanationService.explainWithOutcome(matchId)).thenThrow(new RuntimeException("simulated database failure"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> controller.explainRuleMatch(matchId, requestFrom("1.2.3.4")));
        assertEquals("simulated database failure", ex.getMessage());
    }

    // ---------- 14: rate limiter is consulted unconditionally, AI-enabled state irrelevant to gating ----------

    @Test
    void rateLimiterAcquire_calledForEveryRequest_regardlessOfAiEnabledState() {
        UUID matchId = UUID.randomUUID();
        when(explanationService.explainWithOutcome(matchId))
                .thenReturn(new ExplanationOutcome(fallbackExplanation(matchId), RuleMatchExplanationService.REASON_AI_DISABLED));

        controller.explainRuleMatch(matchId, requestFrom("1.2.3.4"));

        verify(rateLimiter).acquire("1.2.3.4");
        verify(rateLimiter).release();
    }

    // ---------- semaphore release ordering ----------

    @Test
    void rateLimiterRelease_calledEvenWhenServiceThrows() {
        UUID matchId = UUID.randomUUID();
        when(explanationService.explainWithOutcome(matchId)).thenThrow(new RuntimeException("boom"));

        assertThrows(RuntimeException.class, () -> controller.explainRuleMatch(matchId, requestFrom("1.2.3.4")));

        verify(rateLimiter).release();
    }

    @Test
    void rateLimiterRelease_notCalled_whenAcquireItselfThrows() {
        UUID matchId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new RateLimitExceededException("limit exceeded", 5))
                .when(rateLimiter).acquire("1.2.3.4");

        assertThrows(RateLimitExceededException.class, () -> controller.explainRuleMatch(matchId, requestFrom("1.2.3.4")));

        verify(rateLimiter, never()).release();
    }
}
