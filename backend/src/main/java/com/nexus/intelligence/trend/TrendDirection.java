package com.nexus.intelligence.trend;

/**
 * The direction of a numeric series. INCREASING and DECREASING are DESCRIPTIVE (the readings rose or fell by more than the sensor resolution and the noise);
 * whether the direction is also statistically established is a separate flag on the result. INSUFFICIENT_EVIDENCE says the platform looked and cannot say;
 * NOT_APPLICABLE is a capability that is not a continuous numeric reading (boolean, text, object).
 */
public enum TrendDirection {
    INCREASING, DECREASING, STABLE, INSUFFICIENT_EVIDENCE, NOT_APPLICABLE
}
