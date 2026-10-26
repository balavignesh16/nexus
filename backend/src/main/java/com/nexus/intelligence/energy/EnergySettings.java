package com.nexus.intelligence.energy;

import java.util.List;

/**
 * Bounds and vocabulary of the energy analysis ({@code nexus.intelligence.energy.*}). Energy is only ever derived from readings the platform STORED: a capability whose
 * key is in {@code powerKeys} is a power reading (W, kW or mW), one in {@code energyKeys} a cumulative energy counter (Wh or kWh). Nothing here is a nominal rating or a
 * guess: a device with no such reading has no energy figure. A gap longer than {@code gapFactor} times the median sampling interval (at most {@code maxGapSeconds}, at
 * least {@code minGapSeconds}) is not integrated: power is unknown there, and the uncovered time is reported.
 */
public record EnergySettings(int windowHours, List<String> powerKeys, List<String> energyKeys, double gapFactor, int minGapSeconds, int maxGapSeconds, int maxSamples, int maxDevices, int topN,
                             int skewToleranceSeconds) {
    public EnergySettings {
        if (windowHours < 1 || windowHours > 720) throw new IllegalStateException("nexus.intelligence.energy.window-hours must be 1 to 720");
        if (powerKeys == null || powerKeys.isEmpty() || powerKeys.size() > 20) throw new IllegalStateException("nexus.intelligence.energy.power-keys needs 1 to 20 capability keys");
        if (energyKeys == null || energyKeys.size() > 20) throw new IllegalStateException("nexus.intelligence.energy.energy-keys needs at most 20 capability keys");
        for (String k : powerKeys) if (!k.matches("[a-z][a-z0-9_]{0,99}")) throw new IllegalStateException("a power key is not a capability key: " + k);
        for (String k : energyKeys) if (!k.matches("[a-z][a-z0-9_]{0,99}")) throw new IllegalStateException("an energy key is not a capability key: " + k);
        if (gapFactor < 2.0 || gapFactor > 50.0) throw new IllegalStateException("nexus.intelligence.energy.gap-factor must be 2 to 50");
        if (minGapSeconds < 1 || minGapSeconds > 3_600) throw new IllegalStateException("nexus.intelligence.energy.min-gap-seconds must be 1 to 3600");
        if (maxGapSeconds < minGapSeconds || maxGapSeconds > 86_400) throw new IllegalStateException("nexus.intelligence.energy.max-gap-seconds must be at least the minimum and at most 86400");
        if (maxSamples < 100 || maxSamples > 20_000) throw new IllegalStateException("nexus.intelligence.energy.max-samples must be 100 to 20000");
        if (maxDevices < 1 || maxDevices > 500) throw new IllegalStateException("nexus.intelligence.energy.max-devices must be 1 to 500");
        if (topN < 1 || topN > 50) throw new IllegalStateException("nexus.intelligence.energy.top-n must be 1 to 50");
        if (skewToleranceSeconds < 0 || skewToleranceSeconds > 3_600) throw new IllegalStateException("nexus.intelligence.energy.skew-tolerance-seconds must be 0 to 3600");
        powerKeys = List.copyOf(powerKeys);
        energyKeys = List.copyOf(energyKeys);
    }

    public static EnergySettings defaults() {
        return new EnergySettings(24, List.of("power", "active_power", "power_w"), List.of("energy", "energy_total", "energy_kwh"), 5.0, 60, 21_600, 5_000, 200, 5, 300);
    }
}
