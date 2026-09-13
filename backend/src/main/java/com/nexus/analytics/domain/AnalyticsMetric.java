package com.nexus.analytics.domain;

/**
 * The bounded set of time-series metrics Analytics exposes (Frontend 2.0
 * Phase 2). Each maps to exactly one existing, real, persisted table -
 * never a fabricated/derived-without-a-source metric.
 */
public enum AnalyticsMetric {
    TELEMETRY,
    EVENTS,
    RULE_MATCHES,
    ACTIONS,
    // Pipeline traces retain only 7 days (nexus.retention.pipeline-days) -
    // far shorter than every other metric here. AnalyticsService clamps
    // the effective query window for this metric and reports the actual
    // window used, rather than implying more history exists than does.
    PIPELINE;

    public static AnalyticsMetric fromParam(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("metric is required");
        }
        return switch (raw) {
            case "telemetry" -> TELEMETRY;
            case "events" -> EVENTS;
            case "rule-matches" -> RULE_MATCHES;
            case "actions" -> ACTIONS;
            case "pipeline" -> PIPELINE;
            default -> throw new IllegalArgumentException(
                    "Invalid metric '" + raw + "'. Must be one of: telemetry, events, rule-matches, actions, pipeline");
        };
    }
}
