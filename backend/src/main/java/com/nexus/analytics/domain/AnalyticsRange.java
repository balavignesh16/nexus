package com.nexus.analytics.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The bounded set of time ranges Analytics accepts (Frontend 2.0 Phase 2).
 * Deliberately closed - no arbitrary/unbounded client-supplied range is
 * ever accepted, so every analytics query has an explicit, small upper
 * bound on how much data it can scan.
 */
public enum AnalyticsRange {
    ONE_HOUR(Duration.ofHours(1), AnalyticsBucket.MINUTE),
    SIX_HOURS(Duration.ofHours(6), AnalyticsBucket.FIVE_MINUTES),
    ONE_DAY(Duration.ofDays(1), AnalyticsBucket.HOUR),
    SEVEN_DAYS(Duration.ofDays(7), AnalyticsBucket.HOUR),
    THIRTY_DAYS(Duration.ofDays(30), AnalyticsBucket.DAY);

    private final Duration duration;
    private final AnalyticsBucket defaultBucket;

    AnalyticsRange(Duration duration, AnalyticsBucket defaultBucket) {
        this.duration = duration;
        this.defaultBucket = defaultBucket;
    }

    public Duration duration() {
        return duration;
    }

    public AnalyticsBucket defaultBucket() {
        return defaultBucket;
    }

    public Instant from(Instant now) {
        return now.minus(duration);
    }

    /**
     * Parses the {@code range} query parameter's exact accepted literal
     * values ("1h","6h","24h","7d","30d"). Any other value is rejected as
     * a client error, never silently defaulted.
     */
    public static AnalyticsRange fromParam(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("range is required");
        }
        return switch (raw) {
            case "1h" -> ONE_HOUR;
            case "6h" -> SIX_HOURS;
            case "24h" -> ONE_DAY;
            case "7d" -> SEVEN_DAYS;
            case "30d" -> THIRTY_DAYS;
            default -> throw new IllegalArgumentException(
                    "Invalid range '" + raw + "'. Must be one of: 1h, 6h, 24h, 7d, 30d");
        };
    }
}
