package com.nexus.intelligence.anomaly;

/**
 * Documented, validated configuration of the deterministic detectors. Nothing here is a universal per-sensor constant: every scale is estimated from the
 * series' own baseline (median and MAD of its readings, its own reporting cadence, its own smallest reading step); these values only fix how much evidence
 * is required and how many robust deviations count as unusual.
 *
 * <ul>
 *   <li>{@code outlierK} 4.0: cut-off for the modified z-score 0.6745 |x - median| / MAD. The textbook screening value is 3.5, but a series is judged on
 *       about a hundred recent readings, and under Gaussian noise 3.5 flags roughly one clean series in twenty (4.7e-4 per reading) while 4.0 flags under one in a
 *       hundred (6.3e-5 per reading). Real sensors have heavier tails, so the measured rate in the evaluation is the figure to quote, not these.</li>
 *   <li>{@code spikeK} 5.0: the same robust score applied to the change between consecutive readings, which is more heavy-tailed than the readings, so the cut-off is higher.</li>
 *   <li>{@code minBaselineSamples} 100: the robust scale is estimated from the baseline, and a small baseline gives an unstable scale. Measured on clean Gaussian
 *       noise (BaselineSizeSweepTest): ordinary readings were falsely flagged as outliers in 9.3% of series at 20 baseline readings, 5.5% at 30, 3.3% at 50, 1.3% at 100 and
 *       0.2% at 300; so below 100 the service answers INSUFFICIENT_EVIDENCE. A slow reporter needs a wider window (up to 720 hours) to reach it.</li>
 *   <li>{@code minRecentSamples} 10 for flatline: a handful of equal readings is normal for a slowly changing quantity.</li>
 *   <li>{@code gapFactor} 5 and {@code minGapSeconds} 60: silence is notable only beyond five of the device's OWN median reporting intervals and a minute.</li>
 *   <li>{@code skewToleranceSeconds} 300: a reading event-stamped more than this after the backend's evaluation instant is excluded and counted, never analysed.</li>
 * </ul>
 */
public record AnomalySettings(int windowHours, int recentMinutes, int minBaselineSamples, int minRecentSamples, double outlierK, double spikeK, double gapFactor,
                              int minGapSeconds, int skewToleranceSeconds, int maxSamples, int maxCapabilities, int maxFindingMeasurements) {
    public AnomalySettings {
        if (windowHours < 1 || windowHours > 720) throw new IllegalStateException("nexus.intelligence.anomaly.window-hours must be 1 to 720 (30 days)");
        if (recentMinutes < 5 || recentMinutes > windowHours * 30) throw new IllegalStateException("nexus.intelligence.anomaly.recent-minutes must be 5 up to half the window");
        if (minBaselineSamples < 10 || minBaselineSamples > 1000) throw new IllegalStateException("nexus.intelligence.anomaly.min-baseline-samples must be 10 to 1000");
        if (minRecentSamples < 3 || minRecentSamples > 1000) throw new IllegalStateException("nexus.intelligence.anomaly.min-recent-samples must be 3 to 1000");
        if (outlierK < 2.0 || outlierK > 10.0) throw new IllegalStateException("nexus.intelligence.anomaly.outlier-k must be 2 to 10");
        if (spikeK < 2.0 || spikeK > 20.0) throw new IllegalStateException("nexus.intelligence.anomaly.spike-k must be 2 to 20");
        if (gapFactor < 2.0 || gapFactor > 100.0) throw new IllegalStateException("nexus.intelligence.anomaly.gap-factor must be 2 to 100");
        if (minGapSeconds < 1 || minGapSeconds > 86_400) throw new IllegalStateException("nexus.intelligence.anomaly.min-gap-seconds must be 1 to 86400");
        if (skewToleranceSeconds < 0 || skewToleranceSeconds > 3_600) throw new IllegalStateException("nexus.intelligence.anomaly.skew-tolerance-seconds must be 0 to 3600");
        if (maxSamples < 100 || maxSamples > 20_000) throw new IllegalStateException("nexus.intelligence.anomaly.max-samples must be 100 to 20000");
        if (maxCapabilities < 1 || maxCapabilities > 50) throw new IllegalStateException("nexus.intelligence.anomaly.max-capabilities must be 1 to 50");
        if (maxFindingMeasurements < 1 || maxFindingMeasurements > 50) throw new IllegalStateException("nexus.intelligence.anomaly.max-finding-measurements must be 1 to 50");
    }

    public static AnomalySettings defaults() {
        return new AnomalySettings(24, 120, 100, 10, 4.0, 5.0, 5.0, 60, 300, 5_000, 20, 10);
    }

    public AnomalySettings withWindow(int hours, int recentMinutes) {
        return new AnomalySettings(hours, recentMinutes, minBaselineSamples, minRecentSamples, outlierK, spikeK, gapFactor, minGapSeconds, skewToleranceSeconds, maxSamples, maxCapabilities, maxFindingMeasurements);
    }
}
