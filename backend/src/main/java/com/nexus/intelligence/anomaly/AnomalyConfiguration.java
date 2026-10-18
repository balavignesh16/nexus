package com.nexus.intelligence.anomaly;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds the detector settings from {@code nexus.intelligence.anomaly.*}; an out-of-range value stops startup. */
@Configuration
public class AnomalyConfiguration {
    @Bean
    AnomalySettings anomalySettings(@Value("${nexus.intelligence.anomaly.window-hours:24}") int windowHours,
                                    @Value("${nexus.intelligence.anomaly.recent-minutes:120}") int recentMinutes,
                                    @Value("${nexus.intelligence.anomaly.min-baseline-samples:100}") int minBaseline,
                                    @Value("${nexus.intelligence.anomaly.min-recent-samples:10}") int minRecent,
                                    @Value("${nexus.intelligence.anomaly.outlier-k:4.0}") double outlierK,
                                    @Value("${nexus.intelligence.anomaly.spike-k:5.0}") double spikeK,
                                    @Value("${nexus.intelligence.anomaly.gap-factor:5.0}") double gapFactor,
                                    @Value("${nexus.intelligence.anomaly.min-gap-seconds:60}") int minGapSeconds,
                                    @Value("${nexus.intelligence.anomaly.skew-tolerance-seconds:300}") int skew,
                                    @Value("${nexus.intelligence.anomaly.max-samples:5000}") int maxSamples,
                                    @Value("${nexus.intelligence.anomaly.max-capabilities:20}") int maxCapabilities,
                                    @Value("${nexus.intelligence.anomaly.max-finding-measurements:10}") int maxMeasurements) {
        return new AnomalySettings(windowHours, recentMinutes, minBaseline, minRecent, outlierK, spikeK, gapFactor, minGapSeconds, skew, maxSamples, maxCapabilities, maxMeasurements);
    }
}
