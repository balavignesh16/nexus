package com.nexus.intelligence.energy;

import java.time.Instant;
import java.util.List;

/**
 * The energy derived from one device's stored readings. {@code kind} says how the figure was obtained: METERED_COUNTER (the difference of a cumulative energy counter
 * the device itself reported) or INTEGRATED_FROM_POWER (an ESTIMATE: power readings integrated between samples with the trapezoid rule, never a metered value).
 * {@code coverage} is the share of the sampled span that was integrated; the rest is reported as gaps, not filled.
 */
public record EnergyResult(Status status, Kind kind, String capabilityKey, String inputUnit, Double energyWh, Double averagePowerW, Double peakPowerW, int samples, Instant firstSampleAt, Instant lastSampleAt,
                           double spanSeconds, double coveredSeconds, double coverage, int gaps, double gapSeconds, int counterResets, List<String> notes, String reason) {
    public enum Status { OK, INSUFFICIENT_EVIDENCE, NOT_APPLICABLE }

    public enum Kind { METERED_COUNTER, INTEGRATED_FROM_POWER }

    public EnergyResult {
        notes = List.copyOf(notes);
    }
}
