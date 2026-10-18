package com.nexus.intelligence.model;

import java.time.Duration;

/** Bounds on every model call. Validated at construction; the defaults are conservative. */
public record GuardSettings(int maxConcurrent, Duration timeout, int maxRetries, int breakerFailureThreshold, Duration breakerOpenFor, int maxContextChars, int maxOutputChars) {
    public GuardSettings {
        if (maxConcurrent < 1 || maxConcurrent > 16) throw new IllegalStateException("nexus.ai.guard.max-concurrent must be 1 to 16");
        if (timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofSeconds(60)) > 0) throw new IllegalStateException("nexus.ai.guard.timeout must be 100 ms to 60 s");
        if (maxRetries < 0 || maxRetries > 2) throw new IllegalStateException("nexus.ai.guard.max-retries must be 0 to 2");
        if (breakerFailureThreshold < 1 || breakerFailureThreshold > 100) throw new IllegalStateException("nexus.ai.guard.breaker-failures must be 1 to 100");
        if (breakerOpenFor.compareTo(Duration.ofSeconds(1)) < 0 || breakerOpenFor.compareTo(Duration.ofHours(1)) > 0) throw new IllegalStateException("nexus.ai.guard.breaker-open must be 1 s to 1 h");
        if (maxContextChars < 500 || maxContextChars > 100_000) throw new IllegalStateException("nexus.ai.guard.max-context-chars must be 500 to 100000");
        if (maxOutputChars < 200 || maxOutputChars > 100_000) throw new IllegalStateException("nexus.ai.guard.max-output-chars must be 200 to 100000");
    }

    public static GuardSettings defaults() {
        return new GuardSettings(4, Duration.ofSeconds(8), 1, 5, Duration.ofSeconds(30), 8_000, 20_000);
    }
}
