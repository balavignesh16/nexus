package com.nexus.intelligence.energy;

import com.nexus.intelligence.anomaly.CleanedSeries;
import com.nexus.intelligence.anomaly.SeriesCleaner;
import com.nexus.intelligence.anomaly.TelemetrySeriesReader;
import com.nexus.intelligence.energy.EnergyResult.Kind;
import com.nexus.intelligence.energy.EnergyResult.Status;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Energy and usage from STORED power or energy readings, per device and across devices. Read-only (one read-only transaction with a statement timeout, bounded window,
 * samples and devices); nothing is written, nothing is sent, no model is involved.
 *
 * <p>Honesty rules. A device with no power or energy reading has no figure: the report says energy cannot be reported (and what to install), it never uses a rating or an
 * average. Provenance is explicit: a device whose registered mode is SIMULATED is labelled SIMULATED, a REAL one MEASURED, and each energy figure says whether it is a
 * metered counter difference or an ESTIMATE integrated from power; totals are kept apart per provenance and per kind and are never added across them. Gaps are reported,
 * not filled. Savings are never estimated.
 */
@Service
public class EnergyService {
    static final int STATEMENT_TIMEOUT_MILLIS = 5_000;

    public record DeviceEnergy(UUID deviceId, String name, String provenance, EnergyResult result, Double shareOfGroup) {}

    public record Total(String provenance, Kind kind, double energyWh, int devices) {}

    public record Report(UUID scope, boolean deviceFound, Instant evaluatedAt, Instant windowFrom, Instant windowTo, boolean available, String unavailableReason, List<DeviceEnergy> devices,
                         List<DeviceEnergy> topConsumers, List<Total> totals, boolean candidatesTruncated, List<String> notes) {}

    private final EnergyReader reader;
    private final TelemetrySeriesReader series;
    private final EnergySettings settings;
    private final Clock clock;

    public EnergyService(EnergyReader reader, TelemetrySeriesReader series, EnergySettings settings, Clock clock) {
        this.reader = reader;
        this.series = series;
        this.settings = settings;
        this.clock = clock;
    }

    public EnergySettings settings() {
        return settings;
    }

    @Transactional(readOnly = true)
    public Report analyse(Optional<UUID> device, Optional<Integer> windowHours) {
        Instant now = clock.instant();
        reader.boundStatements(STATEMENT_TIMEOUT_MILLIS);
        int hours = Math.max(1, Math.min(windowHours.orElse(settings.windowHours()), 720));
        Instant from = now.minus(Duration.ofHours(hours));
        if (device.isPresent() && !reader.deviceExists(device.get())) return new Report(device.get(), false, now, from, now, false, "no device has this id", List.of(), List.of(), List.of(), false, List.of());
        List<String> keys = new ArrayList<>(settings.energyKeys());
        keys.addAll(settings.powerKeys());
        List<EnergyReader.Candidate> candidates = reader.candidates(keys, from, now, device.orElse(null), settings.maxDevices() * 4 + 1);
        Map<UUID, List<EnergyReader.Candidate>> byDevice = new LinkedHashMap<>();
        for (EnergyReader.Candidate c : candidates) byDevice.computeIfAbsent(c.deviceId(), k -> new ArrayList<>()).add(c);
        boolean truncated = byDevice.size() > settings.maxDevices() || candidates.size() > settings.maxDevices() * 4;
        List<DeviceEnergy> rows = new ArrayList<>();
        int taken = 0;
        for (Map.Entry<UUID, List<EnergyReader.Candidate>> e : byDevice.entrySet()) {
            if (taken++ >= settings.maxDevices()) break;
            // a metered counter wins over integrating power: it is the device's own energy figure
            EnergyReader.Candidate chosen = null;
            for (String key : keys) {
                for (EnergyReader.Candidate c : e.getValue()) if (c.capabilityKey().equals(key)) {
                    chosen = c;
                    break;
                }
                if (chosen != null) break;
            }
            EnergyResult result = compute(chosen, from, now);
            rows.add(new DeviceEnergy(chosen.deviceId(), chosen.deviceName(), "SIMULATED".equalsIgnoreCase(chosen.deviceMode()) ? "SIMULATED" : "MEASURED", result, null));
        }
        // shares are within a provenance and a kind: simulated and real, metered and estimated figures are never mixed
        Map<String, Double> groupTotals = new TreeMap<>();
        for (DeviceEnergy r : rows) if (r.result().status() == Status.OK) groupTotals.merge(r.provenance() + "|" + r.result().kind(), r.result().energyWh(), Double::sum);
        List<DeviceEnergy> withShare = new ArrayList<>();
        for (DeviceEnergy r : rows) {
            Double share = null;
            if (r.result().status() == Status.OK) {
                double total = groupTotals.get(r.provenance() + "|" + r.result().kind());
                share = total > 0 ? r.result().energyWh() / total : null;
            }
            withShare.add(new DeviceEnergy(r.deviceId(), r.name(), r.provenance(), r.result(), share));
        }
        List<DeviceEnergy> top = withShare.stream().filter(r -> r.result().status() == Status.OK).sorted(Comparator.comparingDouble((DeviceEnergy r) -> r.result().energyWh()).reversed()).limit(settings.topN()).toList();
        List<Total> totals = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        for (DeviceEnergy r : withShare) if (r.result().status() == Status.OK) counts.merge(r.provenance() + "|" + r.result().kind(), 1, Integer::sum);
        for (Map.Entry<String, Double> g : groupTotals.entrySet()) {
            String[] parts = g.getKey().split("[|]");
            totals.add(new Total(parts[0], Kind.valueOf(parts[1]), g.getValue(), counts.get(g.getKey())));
        }
        boolean any = !withShare.isEmpty();
        String why = any ? null : "no power or energy reading (capability " + String.join(", ", settings.energyKeys()) + " or " + String.join(", ", settings.powerKeys()) + ") is stored in the window";
        List<String> notes = new ArrayList<>();
        if (truncated) notes.add("More than " + settings.maxDevices() + " devices have energy readings; only the first " + settings.maxDevices() + " were analysed.");
        return new Report(device.orElse(null), true, now, from, now, any, why, withShare, top, totals, truncated, notes);
    }

    private EnergyResult compute(EnergyReader.Candidate c, Instant from, Instant now) {
        TelemetrySeriesReader.Read read = series.read(c.deviceId(), c.capabilityKey(), from, now, settings.maxSamples());
        CleanedSeries cleaned = SeriesCleaner.clean(read.rows(), now, settings.skewToleranceSeconds(), read.truncated());
        boolean counter = settings.energyKeys().contains(c.capabilityKey());
        Kind kind = counter ? Kind.METERED_COUNTER : Kind.INTEGRATED_FROM_POWER;
        switch (cleaned.applicability()) {
            case NOT_NUMERIC, MIXED_VALUE_TYPES, MIXED_UNITS -> {
                return new EnergyResult(Status.NOT_APPLICABLE, kind, c.capabilityKey(), cleaned.unit(), null, null, null, 0, null, null, 0, 0, 0, 0, 0, 0, List.of(),
                        cleaned.applicability() == CleanedSeries.Applicability.MIXED_UNITS ? "the readings mix units, so no single series can be integrated" : "the readings are not numeric");
            }
            case EMPTY -> {
                return new EnergyResult(Status.INSUFFICIENT_EVIDENCE, kind, c.capabilityKey(), null, null, null, null, 0, null, null, 0, 0, 0, 0, 0, 0, List.of(), "no readings were stored in the window");
            }
            default -> { /* numeric below */ }
        }
        EnergyResult result = counter ? EnergyAnalyzer.fromCounter(cleaned.samples(), cleaned.unit(), c.capabilityKey()) : EnergyAnalyzer.fromPower(cleaned.samples(), cleaned.unit(), c.capabilityKey(), settings);
        if (read.truncated() && result.status() == Status.OK) {
            List<String> notes = new ArrayList<>(result.notes());
            notes.add("The read limit of " + settings.maxSamples() + " readings was reached: only the newest readings of the window were used.");
            return new EnergyResult(result.status(), result.kind(), result.capabilityKey(), result.inputUnit(), result.energyWh(), result.averagePowerW(), result.peakPowerW(), result.samples(), result.firstSampleAt(), result.lastSampleAt(),
                    result.spanSeconds(), result.coveredSeconds(), result.coverage(), result.gaps(), result.gapSeconds(), result.counterResets(), notes, null);
        }
        return result;
    }
}
