package com.nexus.intelligence.recommend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How much persisted evidence a recommendation needs, and the bounds on a request. {@code minFailures} 3 and {@code minConflicts} 3: one or two dead
 * letters or suppressions are ordinary and are reported as INSUFFICIENT_EVIDENCE with their count, not recommended. {@code minThresholdSamples} 100: a
 * percentile of fewer readings says little. The cap and the device and rule limits make the cost of any request a constant.
 */
public record RecommendationSettings(int minFailures, int minConflicts, int minThresholdSamples, int staleOfflineHours, int perScopeCap, int maxDevices, int maxRules,
                                     int factHours, double neverFiredOrAlwaysTrueFraction) {
    public RecommendationSettings {
        if (minFailures < 1 || minFailures > 1000) throw new IllegalStateException("nexus.intelligence.recommend.min-failures must be 1 to 1000");
        if (minConflicts < 1 || minConflicts > 1000) throw new IllegalStateException("nexus.intelligence.recommend.min-conflicts must be 1 to 1000");
        if (minThresholdSamples < 30 || minThresholdSamples > 5000) throw new IllegalStateException("nexus.intelligence.recommend.min-threshold-samples must be 30 to 5000");
        if (staleOfflineHours < 1 || staleOfflineHours > 720) throw new IllegalStateException("nexus.intelligence.recommend.stale-offline-hours must be 1 to 720");
        if (perScopeCap < 1 || perScopeCap > 200) throw new IllegalStateException("nexus.intelligence.recommend.per-scope-cap must be 1 to 200");
        if (maxDevices < 1 || maxDevices > 200) throw new IllegalStateException("nexus.intelligence.recommend.max-devices must be 1 to 200");
        if (maxRules < 1 || maxRules > 200) throw new IllegalStateException("nexus.intelligence.recommend.max-rules must be 1 to 200");
        if (factHours < 1 || factHours > 720) throw new IllegalStateException("nexus.intelligence.recommend.fact-hours must be 1 to 720 (30 days)");
        if (neverFiredOrAlwaysTrueFraction <= 0.0 || neverFiredOrAlwaysTrueFraction > 1.0) throw new IllegalStateException("nexus.intelligence.recommend.always-true-fraction must be in (0, 1]");
    }

    public static RecommendationSettings defaults() {
        return new RecommendationSettings(3, 3, 100, 24, 50, 50, 50, 168, 0.5);
    }

    @Configuration
    static class Binding {
        @Bean
        RecommendationSettings recommendationSettings(@Value("${nexus.intelligence.recommend.min-failures:3}") int minFailures,
                                                      @Value("${nexus.intelligence.recommend.min-conflicts:3}") int minConflicts,
                                                      @Value("${nexus.intelligence.recommend.min-threshold-samples:100}") int minThresholdSamples,
                                                      @Value("${nexus.intelligence.recommend.stale-offline-hours:24}") int staleOfflineHours,
                                                      @Value("${nexus.intelligence.recommend.per-scope-cap:50}") int perScopeCap,
                                                      @Value("${nexus.intelligence.recommend.max-devices:50}") int maxDevices,
                                                      @Value("${nexus.intelligence.recommend.max-rules:50}") int maxRules,
                                                      @Value("${nexus.intelligence.recommend.fact-hours:168}") int factHours,
                                                      @Value("${nexus.intelligence.recommend.always-true-fraction:0.5}") double fraction) {
            return new RecommendationSettings(minFailures, minConflicts, minThresholdSamples, staleOfflineHours, perScopeCap, maxDevices, maxRules, factHours, fraction);
        }
    }
}
