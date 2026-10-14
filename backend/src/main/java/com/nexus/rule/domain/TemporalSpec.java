package com.nexus.rule.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/**
 * Temporal wrapper for one condition. The condition's own capability/operator/expectedValue remains the
 * underlying predicate for FOR_DURATION and COUNT_WITHIN; TREND uses direction/minChange; ABSENCE_FOR and
 * OFFLINE_FOR use only the scope and duration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TemporalSpec(TemporalOperator operator, Long durationSeconds, Long windowSeconds, Integer count,
        Direction direction, BigDecimal minChange, CountMode countMode) {
    public enum Direction { RISING, FALLING }
    /** COUNT_WITHIN counts qualified samples by default; EDGES counts only false-to-true predicate transitions. */
    public enum CountMode { SAMPLES, EDGES }
    public TemporalSpec(TemporalOperator operator, Long durationSeconds, Long windowSeconds, Integer count, Direction direction, BigDecimal minChange) {
        this(operator, durationSeconds, windowSeconds, count, direction, minChange, null);
    }
    public CountMode mode() { return countMode == null ? CountMode.SAMPLES : countMode; }
    public static final int MAX_WINDOW_SECONDS=86_400;
    public static final int MAX_COUNT=200;   // == the V28 member cap: a configured count must be storable without truncating evidence
    public static final int MIN_TREND_SAMPLES=3;
}
