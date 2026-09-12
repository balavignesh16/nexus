package com.nexus.rule.ai;

/**
 * Thrown by ExplanationRateLimiter (Tier 4 Phase 2C) when a caller cannot
 * immediately obtain either a per-IP token or a global concurrency permit.
 * Carries only a bounded retry-after hint - no internal limiter state.
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
