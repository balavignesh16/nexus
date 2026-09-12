package com.nexus.rule.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Low-cardinality Micrometer metrics for the AI explanation endpoint (Tier
 * 4 Phase 2C). All tag values are drawn exclusively from
 * RuleMatchExplanationService's fixed REASON_* constants - never matchId,
 * ruleId, deviceId, correlationId, or requestId.
 */
@Component
public class AiExplanationMetrics {

    private final MeterRegistry registry;
    private Timer latencyTimer;
    private Counter rateLimitedCounter;

    public AiExplanationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    void init() {
        this.latencyTimer = registry.timer("nexus.ai.explanation.latency");
        this.rateLimitedCounter = registry.counter("nexus.ai.explanation.rate_limited");
    }

    /**
     * Records one completed explanation request. reason must be one of
     * RuleMatchExplanationService's REASON_* constants. AI_SUCCESS maps to
     * outcome=AI_SUCCESS with no fallback-reason counter increment; every
     * other reason maps to outcome=DETERMINISTIC_FALLBACK plus a
     * fallback-reason increment for that specific reason.
     */
    public void recordOutcome(String reason, long latencyMs) {
        boolean success = RuleMatchExplanationService.REASON_AI_SUCCESS.equals(reason);
        String outcome = success ? "AI_SUCCESS" : "DETERMINISTIC_FALLBACK";
        registry.counter("nexus.ai.explanation.requests", "outcome", outcome).increment();
        if (!success) {
            registry.counter("nexus.ai.explanation.fallback", "reason", reason).increment();
        }
        latencyTimer.record(Duration.ofMillis(Math.max(0, latencyMs)));
    }

    /** A request rejected by ExplanationRateLimiter never reaches explain() - not counted as an explanation request. */
    public void recordRateLimited() {
        rateLimitedCounter.increment();
    }
}
