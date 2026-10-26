package com.nexus.intelligence.energy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/** Binds {@code nexus.intelligence.energy.*}; an out-of-range value stops startup. */
@Configuration
public class EnergyConfiguration {
    @Bean
    EnergySettings energySettings(@Value("${nexus.intelligence.energy.window-hours:24}") int windowHours,
                                  @Value("${nexus.intelligence.energy.power-keys:power,active_power,power_w}") String powerKeys,
                                  @Value("${nexus.intelligence.energy.energy-keys:energy,energy_total,energy_kwh}") String energyKeys,
                                  @Value("${nexus.intelligence.energy.gap-factor:5.0}") double gapFactor,
                                  @Value("${nexus.intelligence.energy.min-gap-seconds:60}") int minGap,
                                  @Value("${nexus.intelligence.energy.max-gap-seconds:21600}") int maxGap,
                                  @Value("${nexus.intelligence.energy.max-samples:5000}") int maxSamples,
                                  @Value("${nexus.intelligence.energy.max-devices:200}") int maxDevices,
                                  @Value("${nexus.intelligence.energy.top-n:5}") int topN,
                                  @Value("${nexus.intelligence.energy.skew-tolerance-seconds:300}") int skew) {
        return new EnergySettings(windowHours, split(powerKeys), split(energyKeys), gapFactor, minGap, maxGap, maxSamples, maxDevices, topN, skew);
    }

    private static List<String> split(String csv) {
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
