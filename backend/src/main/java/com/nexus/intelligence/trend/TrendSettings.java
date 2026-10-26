package com.nexus.intelligence.trend;

/**
 * Documented, validated thresholds of the deterministic trend analysis ({@code nexus.intelligence.trend.*}). None is a universal physical constant: the
 * noise and the resolution are estimated from the series itself; these values only fix how much evidence is needed.
 *
 * <ul>
 *   <li>{@code minDescriptiveSamples} 3: fewer readings cannot show a direction.</li>
 *   <li>{@code minEstablishedSamples} 10 and {@code minEstablishedSpanSeconds} 600: a direction is called statistically established only with at least ten
 *       readings spread over at least ten minutes, because the Mann-Kendall normal approximation is not meaningful below about ten points.</li>
 *   <li>{@code significanceZ} 1.96: two-sided 5% level of the Mann-Kendall test. It is a screening level, not a guarantee, and it assumes roughly independent
 *       readings, which slowly varying sensors often are not.</li>
 *   <li>{@code maxIntervalCv} 0.5 and {@code gapFactor} 5: sampling is regular enough only if the coefficient of variation of the intervals is at most 0.5 and no
 *       interval exceeds five times the median interval; otherwise no trend is called established.</li>
 *   <li>{@code maxPoints} 200: the slope and the test use at most this many evenly spaced points (a deterministic stride), which bounds the work.</li>
 *   <li>{@code noiseBands} 2: the descriptive direction needs a fitted change larger than the sensor resolution and than this many robust noise deviations.</li>
 * </ul>
 */
public record TrendSettings(int windowHours, int minDescriptiveSamples, int minEstablishedSamples, int minEstablishedSpanSeconds, double significanceZ, double maxIntervalCv,
                            double gapFactor, int maxPoints, double noiseBands, int skewToleranceSeconds, int maxSamples) {
    public TrendSettings {
        if (windowHours < 1 || windowHours > 720) throw new IllegalStateException("nexus.intelligence.trend.window-hours must be 1 to 720");
        if (minDescriptiveSamples < 3 || minDescriptiveSamples > 100) throw new IllegalStateException("nexus.intelligence.trend.min-descriptive-samples must be 3 to 100");
        if (minEstablishedSamples < 10 || minEstablishedSamples > 1_000) throw new IllegalStateException("nexus.intelligence.trend.min-established-samples must be 10 to 1000");
        if (minEstablishedSpanSeconds < 60 || minEstablishedSpanSeconds > 86_400) throw new IllegalStateException("nexus.intelligence.trend.min-established-span-seconds must be 60 to 86400");
        if (significanceZ < 1.0 || significanceZ > 5.0) throw new IllegalStateException("nexus.intelligence.trend.significance-z must be 1 to 5");
        if (maxIntervalCv < 0.1 || maxIntervalCv > 3.0) throw new IllegalStateException("nexus.intelligence.trend.max-interval-cv must be 0.1 to 3");
        if (gapFactor < 2.0 || gapFactor > 100.0) throw new IllegalStateException("nexus.intelligence.trend.gap-factor must be 2 to 100");
        if (maxPoints < 20 || maxPoints > 1_000) throw new IllegalStateException("nexus.intelligence.trend.max-points must be 20 to 1000");
        if (noiseBands < 1.0 || noiseBands > 10.0) throw new IllegalStateException("nexus.intelligence.trend.noise-bands must be 1 to 10");
        if (skewToleranceSeconds < 0 || skewToleranceSeconds > 3_600) throw new IllegalStateException("nexus.intelligence.trend.skew-tolerance-seconds must be 0 to 3600");
        if (maxSamples < 100 || maxSamples > 20_000) throw new IllegalStateException("nexus.intelligence.trend.max-samples must be 100 to 20000");
    }

    public static TrendSettings defaults() {
        return new TrendSettings(6, 3, 10, 600, 1.96, 0.5, 5.0, 200, 2.0, 300, 5_000);
    }
}
