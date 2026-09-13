package com.nexus.analytics.domain;

import java.util.Set;

/**
 * The bounded set of time-bucket granularities Analytics accepts
 * (Frontend 2.0 Phase 2). {@code postgresUnit} is the trusted literal
 * passed as a bind parameter to {@code date_trunc}; {@code intervalLiteral}
 * is the trusted literal passed as a bind parameter cast to
 * {@code interval} for TimescaleDB's {@code time_bucket}. Neither ever
 * comes from unvalidated client input - the client's own bucket string is
 * only ever used to select one of these enum constants, never
 * concatenated into SQL.
 */
public enum AnalyticsBucket {
    MINUTE("minute", "1 minute"),
    FIVE_MINUTES("minute", "5 minutes"),
    HOUR("hour", "1 hour"),
    DAY("day", "1 day");

    private final String postgresUnit;
    private final String intervalLiteral;

    AnalyticsBucket(String postgresUnit, String intervalLiteral) {
        this.postgresUnit = postgresUnit;
        this.intervalLiteral = intervalLiteral;
    }

    public String postgresUnit() {
        return postgresUnit;
    }

    public String intervalLiteral() {
        return intervalLiteral;
    }

    public static AnalyticsBucket fromParam(String raw) {
        return switch (raw) {
            case "minute" -> MINUTE;
            case "5minutes" -> FIVE_MINUTES;
            case "hour" -> HOUR;
            case "day" -> DAY;
            default -> throw new IllegalArgumentException(
                    "Invalid bucket '" + raw + "'. Must be one of: minute, 5minutes, hour, day");
        };
    }

    /**
     * Prevents a pathological query (e.g. 1-minute buckets over a 30-day
     * range = 43,200 points) - each range only accepts a small set of
     * buckets that keep the point count sane for a chart.
     *
     * Deliberately NOT a static field: AnalyticsRange's own enum constants
     * reference AnalyticsBucket constants (e.g. ONE_HOUR's defaultBucket),
     * so a static map here built from AnalyticsRange's constants at
     * AnalyticsBucket's class-init time races AnalyticsRange's own
     * class-init and throws NPE (both enums aren't necessarily fully
     * initialized yet in whichever order the JVM resolves the cycle).
     * Building it fresh per call sidesteps the circular <clinit> entirely.
     */
    private static Set<AnalyticsBucket> allowedFor(AnalyticsRange range) {
        return switch (range) {
            case ONE_HOUR -> Set.of(MINUTE, FIVE_MINUTES);
            case SIX_HOURS -> Set.of(FIVE_MINUTES, HOUR);
            case ONE_DAY -> Set.of(HOUR);
            case SEVEN_DAYS -> Set.of(HOUR, DAY);
            case THIRTY_DAYS -> Set.of(DAY);
        };
    }

    public static void validateForRange(AnalyticsBucket bucket, AnalyticsRange range) {
        Set<AnalyticsBucket> allowed = allowedFor(range);
        if (!allowed.contains(bucket)) {
            throw new IllegalArgumentException(
                    "Bucket '" + bucket + "' is not permitted for range '" + range
                            + "' (would produce too many or too few points). Allowed: " + allowed);
        }
    }
}
