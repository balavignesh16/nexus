package com.nexus.intelligence.trend;

/**
 * A limit that a PERSON configured for a capability, in an explicit unit. Nothing in NEXUS infers a limit from a sensor type: without one, no reading is called
 * dangerous. The comparison is explicit; for a ceiling such as a motor temperature the usual choice is GREATER_OR_EQUAL, so a reading equal to the limit meets it.
 * A backend rule built from this limit is an ordinary software rule: it is not a certified hardware emergency cutoff.
 */
public record HardLimit(double value, String unit, Comparison comparison) {
    public enum Comparison { GREATER_OR_EQUAL, GREATER_THAN }

    public HardLimit {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("a limit must be a finite number");
        if (unit == null || unit.isBlank() || unit.length() > 50) throw new IllegalArgumentException("a limit needs a unit of 1 to 50 characters");
        if (comparison == null) comparison = Comparison.GREATER_OR_EQUAL;
    }

    public boolean met(double reading) {
        return comparison == Comparison.GREATER_OR_EQUAL ? reading >= value : reading > value;
    }
}
