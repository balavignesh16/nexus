package com.nexus.rule.ai;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiExplanationMetrics tests (Tier 4 Phase 2C) using Micrometer's
 * SimpleMeterRegistry - already transitively available via
 * spring-boot-starter-actuator, no new test dependency.
 */
class AiExplanationMetricsTest {

    private SimpleMeterRegistry registry;
    private AiExplanationMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AiExplanationMetrics(registry);
        metrics.init();
    }

    @Test
    void aiSuccess_incrementsSuccessOutcomeOnly() {
        metrics.recordOutcome(RuleMatchExplanationService.REASON_AI_SUCCESS, 42);

        assertEquals(1.0, registry.counter("nexus.ai.explanation.requests", "outcome", "AI_SUCCESS").count());
        assertEquals(0, registry.find("nexus.ai.explanation.fallback").counters().size());
    }

    @Test
    void aiDisabled_incrementsFallbackOutcomeAndReason() {
        metrics.recordOutcome(RuleMatchExplanationService.REASON_AI_DISABLED, 10);

        assertEquals(1.0, registry.counter("nexus.ai.explanation.requests", "outcome", "DETERMINISTIC_FALLBACK").count());
        assertEquals(1.0, registry.counter("nexus.ai.explanation.fallback", "reason", "AI_DISABLED").count());
    }

    @Test
    void providerUnavailable_incrementsAiUnavailableReason() {
        metrics.recordOutcome(RuleMatchExplanationService.REASON_AI_UNAVAILABLE, 10);

        assertEquals(1.0, registry.counter("nexus.ai.explanation.fallback", "reason", "AI_UNAVAILABLE").count());
    }

    @Test
    void parserFailure_incrementsParseFailureReason() {
        metrics.recordOutcome(RuleMatchExplanationService.REASON_AI_PARSE_FAILURE, 10);

        assertEquals(1.0, registry.counter("nexus.ai.explanation.fallback", "reason", "AI_PARSE_FAILURE").count());
    }

    @Test
    void groundingRejection_incrementsGroundingRejectionReason() {
        metrics.recordOutcome(RuleMatchExplanationService.REASON_AI_GROUNDING_REJECTION, 10);

        assertEquals(1.0, registry.counter("nexus.ai.explanation.fallback", "reason", "AI_GROUNDING_REJECTION").count());
    }

    @Test
    void latencyTimer_recordsDuration() {
        metrics.recordOutcome(RuleMatchExplanationService.REASON_AI_SUCCESS, 123);

        assertEquals(1, registry.timer("nexus.ai.explanation.latency").count());
    }

    @Test
    void rateLimited_incrementsRateLimitedCounter_doesNotIncrementRequests() {
        metrics.recordRateLimited();

        assertEquals(1.0, registry.counter("nexus.ai.explanation.rate_limited").count());
        assertEquals(0, registry.find("nexus.ai.explanation.requests").counters().size());
    }

    @Test
    void noIdentifierTagsExistOnAnyMeter() {
        metrics.recordOutcome(RuleMatchExplanationService.REASON_AI_SUCCESS, 1);
        metrics.recordOutcome(RuleMatchExplanationService.REASON_AI_GROUNDING_REJECTION, 1);
        metrics.recordRateLimited();

        for (Meter meter : registry.getMeters()) {
            for (io.micrometer.core.instrument.Tag tag : meter.getId().getTags()) {
                String key = tag.getKey().toLowerCase();
                assertTrue(!key.contains("matchid") && !key.contains("ruleid") && !key.contains("deviceid")
                                && !key.contains("correlationid") && !key.contains("requestid"),
                        "unexpected identifier-shaped tag: " + tag.getKey());
            }
        }
    }
}
