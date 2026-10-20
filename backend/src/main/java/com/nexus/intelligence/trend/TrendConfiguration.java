package com.nexus.intelligence.trend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds {@code nexus.intelligence.trend.*}; an out-of-range value stops startup. */
@Configuration
public class TrendConfiguration {
    @Bean
    TrendSettings trendSettings(@Value("${nexus.intelligence.trend.window-hours:6}") int windowHours,
                                @Value("${nexus.intelligence.trend.min-descriptive-samples:3}") int minDescriptive,
                                @Value("${nexus.intelligence.trend.min-established-samples:10}") int minEstablished,
                                @Value("${nexus.intelligence.trend.min-established-span-seconds:600}") int minSpan,
                                @Value("${nexus.intelligence.trend.significance-z:1.96}") double z,
                                @Value("${nexus.intelligence.trend.max-interval-cv:0.5}") double cv,
                                @Value("${nexus.intelligence.trend.gap-factor:5.0}") double gapFactor,
                                @Value("${nexus.intelligence.trend.max-points:200}") int maxPoints,
                                @Value("${nexus.intelligence.trend.noise-bands:2.0}") double noiseBands,
                                @Value("${nexus.intelligence.trend.skew-tolerance-seconds:300}") int skew,
                                @Value("${nexus.intelligence.trend.max-samples:5000}") int maxSamples) {
        return new TrendSettings(windowHours, minDescriptive, minEstablished, minSpan, z, cv, gapFactor, maxPoints, noiseBands, skew, maxSamples);
    }
}
