package com.nexus.rule.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

/**
 * Narrow, in-process cost/abuse protection for the AI explanation endpoint
 * (Tier 4 Phase 2C) - COST/ABUSE PROTECTION ONLY, not authentication or
 * authorization; it establishes no client identity. Two independent gates,
 * checked in order, neither of which ever blocks or waits:
 *
 *   1. a per-client-IP token bucket (rate: requests-per-minute, capacity: burst)
 *   2. a single global concurrency semaphore (concurrency-limit permits)
 *
 * A caller that cannot immediately obtain both is rejected via
 * RateLimitExceededException - never queued, never made to wait.
 *
 * Single-instance only: state is a plain in-memory ConcurrentHashMap, with
 * no cross-instance synchronization, cache, or persistence - consistent
 * with NEXUS's current single-JVM deployment. Client identity is
 * HttpServletRequest's raw getRemoteAddr(); X-Forwarded-For is
 * deliberately NOT trusted, since no reverse-proxy/trusted-proxy
 * configuration exists anywhere else in this codebase - trusting it behind
 * an untrusted proxy would let a spoofed header bypass or collapse limits.
 */
@Component
public class ExplanationRateLimiter {

    private final int requestsPerMinute;
    private final int burst;
    private final Semaphore concurrencyPermits;
    private final LongSupplier nanoClock;
    private final Map<String, BucketState> buckets = new ConcurrentHashMap<>();

    @Autowired
    public ExplanationRateLimiter(
            @Value("${nexus.ai.rate-limit.requests-per-minute:5}") int requestsPerMinute,
            @Value("${nexus.ai.rate-limit.burst:5}") int burst,
            @Value("${nexus.ai.rate-limit.concurrency-limit:4}") int concurrencyLimit) {
        this(requestsPerMinute, burst, concurrencyLimit, System::nanoTime);
    }

    /** Test-only: injects a deterministic clock instead of System.nanoTime(), avoiding sleep-based tests. */
    ExplanationRateLimiter(int requestsPerMinute, int burst, int concurrencyLimit, LongSupplier nanoClock) {
        this.requestsPerMinute = requestsPerMinute;
        this.burst = burst;
        this.concurrencyPermits = new Semaphore(concurrencyLimit);
        this.nanoClock = nanoClock;
    }

    /**
     * Consumes one token from the caller's bucket, then immediately
     * attempts to acquire one global concurrency permit. Throws
     * RateLimitExceededException without acquiring a permit if the token
     * bucket is exhausted (token bucket is checked first); throws after
     * the token has already been consumed, but without holding a permit,
     * if concurrency is exhausted. Never blocks.
     *
     * A caller that does NOT receive an exception has acquired exactly one
     * concurrency permit and MUST call release() exactly once, in a
     * finally block, once done.
     */
    public void acquire(String clientIp) {
        consumeToken(clientIp);
        if (!concurrencyPermits.tryAcquire()) {
            throw new RateLimitExceededException("AI explanation concurrency limit exceeded", 1);
        }
    }

    public void release() {
        concurrencyPermits.release();
    }

    private void consumeToken(String clientIp) {
        BucketState bucket = buckets.computeIfAbsent(clientIp, ip -> new BucketState(burst, nanoClock.getAsLong()));
        synchronized (bucket) {
            bucket.refill(requestsPerMinute, burst, nanoClock.getAsLong());
            if (bucket.tokens < 1.0) {
                throw new RateLimitExceededException(
                        "AI explanation rate limit exceeded for this client",
                        bucket.secondsUntilNextToken(requestsPerMinute));
            }
            bucket.tokens -= 1.0;
        }
    }

    private static final class BucketState {
        double tokens;
        long lastRefillNanos;

        BucketState(int burst, long nowNanos) {
            this.tokens = burst;
            this.lastRefillNanos = nowNanos;
        }

        void refill(int requestsPerMinute, int burst, long nowNanos) {
            double elapsedSeconds = Math.max(0, nowNanos - lastRefillNanos) / 1_000_000_000.0;
            double refillPerSecond = requestsPerMinute / 60.0;
            tokens = Math.min(burst, tokens + elapsedSeconds * refillPerSecond);
            lastRefillNanos = nowNanos;
        }

        long secondsUntilNextToken(int requestsPerMinute) {
            double refillPerSecond = requestsPerMinute / 60.0;
            double deficit = 1.0 - tokens;
            return Math.max(1, (long) Math.ceil(deficit / refillPerSecond));
        }
    }
}
