package com.nexus.intelligence.trend;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The deterministic description of one numeric series. {@code direction} is DESCRIPTIVE; {@code established} says separately whether it is also supported by a
 * statistical test on enough regular, timestamped readings. {@code rate} exists only when the readings carry timestamps. {@code limit} exists only when a person
 * configured a limit: without one nothing here says a reading is dangerous. Acceleration and trend strength are never reported.
 */
public record TrendResult(TrendDirection direction, String basis, String unit, int sampleCount, Instant windowFrom, Instant windowTo, Double spanSeconds, Double firstValue, Double lastValue,
                          Double observedChange, Double fittedChange, Rate rate, Established established, String shape, double resolution, Double noise, Map<String, Object> quality,
                          LimitAssessment limit, List<String> notes, List<String> limits, String reason, Instant evaluatedAt) {
    /** Per-minute rate of change from the timestamps, with the estimator that produced it. */
    public record Rate(double perMinute, String unit, String method, boolean established) {}

    /** Whether the direction is statistically established, why not, and the test statistic when it was computed. */
    public record Established(boolean established, List<String> reasons, Double mannKendallZ, int pointsUsed) {}

    /** The last reading against a configured limit. status: MET, NOT_MET or UNIT_MISMATCH (no comparison is made). */
    public record LimitAssessment(String status, double limit, String unit, String comparison, Double lastValue, Double margin) {}
}
