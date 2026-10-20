package com.nexus.intelligence.energy;

import com.nexus.intelligence.anomaly.Sample;
import com.nexus.intelligence.energy.EnergyResult.Kind;
import com.nexus.intelligence.energy.EnergyResult.Status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Pure, deterministic energy arithmetic on cleaned, time-ordered readings: no I/O, no clock, no model, no assumption about what a device draws.
 *
 * <p>From POWER readings: the trapezoid rule between consecutive samples (an ESTIMATE of the energy between them), integrating only intervals no longer than the gap
 * limit; a longer interval is a gap, excluded and reported, never filled. From a cumulative ENERGY counter: the sum of its positive increases (a decrease is a counter
 * reset and starts a new baseline; it adds nothing). Units: power W, kW, mW; energy Wh, kWh; anything else, and negative power, is refused rather than converted by guess.
 */
public final class EnergyAnalyzer {
    private EnergyAnalyzer() {}

    private static final String POWER_ESTIMATE = "Energy is an ESTIMATE: the power readings are integrated between samples (trapezoid rule). It is not a metered energy value, and the power between samples was not observed.";
    private static final String COUNTER_NOTE = "Energy is the sum of the increases of a cumulative energy counter that the device reported; it is as accurate as that counter.";

    public static Double powerToWatts(double value, String unit) {
        String u = unit == null ? "" : unit.strip().toLowerCase(Locale.ROOT);
        return switch (u) {
            case "w", "watt", "watts" -> value;
            case "kw", "kilowatt", "kilowatts" -> value * 1000.0;
            case "mw", "milliwatt", "milliwatts" -> value / 1000.0;
            default -> null;
        };
    }

    public static Double energyToWattHours(double value, String unit) {
        String u = unit == null ? "" : unit.strip().toLowerCase(Locale.ROOT);
        return switch (u) {
            case "wh", "watt-hour", "watt-hours" -> value;
            case "kwh", "kilowatt-hour", "kilowatt-hours" -> value * 1000.0;
            default -> null;
        };
    }

    public static EnergyResult fromPower(List<Sample> samples, String unit, String capabilityKey, EnergySettings s) {
        if (powerToWatts(1, unit) == null) return refused(Kind.INTEGRATED_FROM_POWER, capabilityKey, unit, samples.size(), "the unit " + (unit == null ? "(none)" : unit) + " is not a power unit this analysis converts (W, kW, mW)");
        if (samples.size() < 2) return insufficient(Kind.INTEGRATED_FROM_POWER, capabilityKey, unit, samples.size(), samples.isEmpty() ? "no readings were stored in the window" : "at least two readings are needed to integrate power");
        double[] t = new double[samples.size()], p = new double[samples.size()];
        for (int i = 0; i < p.length; i++) {
            t[i] = samples.get(i).at().toEpochMilli() / 1000.0;
            p[i] = powerToWatts(samples.get(i).value(), unit);
            if (!Double.isFinite(p[i]) || p[i] < 0) return insufficient(Kind.INTEGRATED_FROM_POWER, capabilityKey, unit, samples.size(), "negative or non-finite power readings (export or a sensor fault) are not integrated");
        }
        double[] intervals = new double[p.length - 1];
        for (int i = 0; i < intervals.length; i++) intervals[i] = t[i + 1] - t[i];
        double[] sorted = intervals.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        double limit = Math.min(s.maxGapSeconds(), Math.max(s.minGapSeconds(), s.gapFactor() * median));
        double wattSeconds = 0, covered = 0, gapSeconds = 0, peak = 0;
        int gaps = 0;
        for (double v : p) peak = Math.max(peak, v);
        for (int i = 0; i < intervals.length; i++) {
            if (intervals[i] > limit) {
                gaps++;
                gapSeconds += intervals[i];
                continue;
            }
            wattSeconds += (p[i] + p[i + 1]) / 2.0 * intervals[i];
            covered += intervals[i];
        }
        double span = t[t.length - 1] - t[0];
        List<String> notes = new ArrayList<>();
        notes.add(POWER_ESTIMATE);
        if (gaps > 0) notes.add(gaps + " gap(s) totalling " + Math.round(gapSeconds) + " s were longer than " + Math.round(limit) + " s and were NOT integrated: the power during them is unknown.");
        if (covered <= 0) {
            return new EnergyResult(Status.INSUFFICIENT_EVIDENCE, Kind.INTEGRATED_FROM_POWER, capabilityKey, unit, null, null, peak, samples.size(), samples.get(0).at(), samples.get(samples.size() - 1).at(), span, 0, 0, gaps, gapSeconds, 0, notes,
                    "every interval between readings was longer than the gap limit, so nothing could be integrated");
        }
        double wh = wattSeconds / 3600.0;
        return new EnergyResult(Status.OK, Kind.INTEGRATED_FROM_POWER, capabilityKey, unit, wh, wattSeconds / covered, peak, samples.size(), samples.get(0).at(), samples.get(samples.size() - 1).at(), span, covered, span > 0 ? covered / span : 0, gaps,
                gapSeconds, 0, notes, null);
    }

    public static EnergyResult fromCounter(List<Sample> samples, String unit, String capabilityKey) {
        if (energyToWattHours(1, unit) == null) return refused(Kind.METERED_COUNTER, capabilityKey, unit, samples.size(), "the unit " + (unit == null ? "(none)" : unit) + " is not an energy unit this analysis converts (Wh, kWh)");
        if (samples.size() < 2) return insufficient(Kind.METERED_COUNTER, capabilityKey, unit, samples.size(), samples.isEmpty() ? "no readings were stored in the window" : "at least two counter readings are needed");
        double total = 0;
        int resets = 0;
        for (int i = 1; i < samples.size(); i++) {
            double before = samples.get(i - 1).value(), after = samples.get(i).value();
            if (!Double.isFinite(before) || !Double.isFinite(after)) return insufficient(Kind.METERED_COUNTER, capabilityKey, unit, samples.size(), "non-finite counter readings are not used");
            if (after >= before) total += after - before;
            else resets++;
        }
        double wh = energyToWattHours(total, unit);
        double span = Duration.between(samples.get(0).at(), samples.get(samples.size() - 1).at()).toMillis() / 1000.0;
        List<String> notes = new ArrayList<>();
        notes.add(COUNTER_NOTE);
        if (resets > 0) notes.add(resets + " counter reset(s) (a decrease) were found; the energy used while a counter was reset is not known and is not counted.");
        return new EnergyResult(Status.OK, Kind.METERED_COUNTER, capabilityKey, unit, wh, span > 0 ? wh * 3600.0 / span : null, null, samples.size(), samples.get(0).at(), samples.get(samples.size() - 1).at(), span, span, 1.0, 0, 0, resets, notes, null);
    }

    private static EnergyResult insufficient(Kind kind, String key, String unit, int n, String reason) {
        return new EnergyResult(Status.INSUFFICIENT_EVIDENCE, kind, key, unit, null, null, null, n, null, null, 0, 0, 0, 0, 0, 0, List.of(), reason);
    }

    private static EnergyResult refused(Kind kind, String key, String unit, int n, String reason) {
        return new EnergyResult(Status.NOT_APPLICABLE, kind, key, unit, null, null, null, n, null, null, 0, 0, 0, 0, 0, 0, List.of(), reason);
    }
}
