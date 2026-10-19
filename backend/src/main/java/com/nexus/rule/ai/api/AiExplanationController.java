package com.nexus.rule.ai.api;

import com.nexus.rule.ai.AiExplanationMetrics;
import com.nexus.rule.ai.ExplanationRateLimiter;
import com.nexus.rule.ai.RateLimitExceededException;
import com.nexus.rule.ai.RuleMatchExplanationService;
import com.nexus.rule.ai.RuleMatchExplanationService.ExplanationOutcome;
import com.nexus.rule.ai.api.dto.RuleMatchExplanationResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Tier 4 Phase 2C: the sole HTTP exposure of rule-match explanations. This
 * controller is deliberately thin - all AI/grounding/fallback orchestration
 * lives in RuleMatchExplanationService, and all durable evidence retrieval
 * lives in RuleMatchExplanationContextBuilder. Neither AiReasoningBoundary,
 * GroundingChecker, nor OpenAiReasoningBoundary is ever called directly
 * from here.
 *
 * Read-only and non-mutating: this endpoint triggers no rule change, no
 * action, no actuator command, no telemetry write, no twin update, and no
 * event creation - it only explains an already-persisted rule match.
 *
 * Unauthenticated, consistent with the current NEXUS security posture: no
 * endpoint in this codebase performs authentication today. The rate
 * limiter below protects against unbounded request/AI-cost amplification;
 * it establishes no caller identity and is not a substitute for
 * authentication or authorization.
 */
@RestController
@RequestMapping("/api/v1/ai/explanations")
public class AiExplanationController {

    private static final Logger log = LoggerFactory.getLogger(AiExplanationController.class);

    private final RuleMatchExplanationService explanationService;
    private final ExplanationRateLimiter rateLimiter;
    private final AiExplanationMetrics metrics;

    public AiExplanationController(RuleMatchExplanationService explanationService,
                                    ExplanationRateLimiter rateLimiter,
                                    AiExplanationMetrics metrics) {
        this.explanationService = explanationService;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    private com.nexus.intelligence.model.ModelProvider modelProvider;

    /** Optional: names the provider that wrote an AI explanation. Absent in unit tests that build the controller directly. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setModelProvider(com.nexus.intelligence.model.ModelProvider modelProvider) {
        this.modelProvider = modelProvider;
    }

    @GetMapping("/rule-match/{matchId}")
    public ResponseEntity<RuleMatchExplanationResponse> explainRuleMatch(@PathVariable UUID matchId,
                                                                          HttpServletRequest request) {
        String clientIp = request.getRemoteAddr();

        try {
            rateLimiter.acquire(clientIp);
        } catch (RateLimitExceededException e) {
            metrics.recordRateLimited();
            log.info("AI explanation request rate-limited for client {}: {}", clientIp, e.getMessage());
            throw e;
        }

        long startNanos = System.nanoTime();
        try {
            // Unknown match -> ResourceNotFoundException propagates
            // untouched to GlobalExceptionHandler (404). Database/context
            // infrastructure failures propagate untouched as well (500).
            // Only AI-side failures (disabled/unavailable/parse/grounding)
            // are absorbed into a 200 deterministic fallback - that
            // absorption already happens entirely inside
            // RuleMatchExplanationService, not here.
            ExplanationOutcome outcome = explanationService.explainWithOutcome(matchId);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            metrics.recordOutcome(outcome.reason(), latencyMs);
            log.info("AI explanation served for match {}: outcome={}, latencyMs={}", matchId, outcome.reason(), latencyMs);
            return ResponseEntity.ok(RuleMatchExplanationResponse.from(outcome.explanation(), modelProvider == null ? null : modelProvider.identity()));
        } finally {
            rateLimiter.release();
        }
    }
}
