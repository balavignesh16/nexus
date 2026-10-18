package com.nexus.intelligence.anomaly;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic anomaly detection over persisted telemetry. It runs only on request, inside a READ-ONLY transaction (so the database itself refuses any
 * write), with a statement timeout and a bounded number of capabilities and readings, and it never touches the ingestion path. It needs no model. The
 * evaluation instant is the backend clock; reading times are the devices' own event times, so the windows are expressed on that axis and reading time skew
 * is bounded by the settings.
 */
@Service
public class AnomalyService {
    static final int STATEMENT_TIMEOUT_MILLIS = 5_000;

    private final TelemetrySeriesReader reader;
    private final AnomalySettings settings;
    private final Clock clock;

    public AnomalyService(TelemetrySeriesReader reader, AnomalySettings settings, Clock clock) {
        this.reader = reader;
        this.settings = settings;
        this.clock = clock;
    }

    public AnomalySettings settings() {
        return settings;
    }

    @Transactional(readOnly = true)
    public AnomalyReport analyse(UUID device, Optional<String> capability, Optional<Integer> windowHours, Optional<Integer> recentMinutes) {
        Instant now = clock.instant();
        AnomalySettings s = settings.withWindow(windowHours.orElse(settings.windowHours()), recentMinutes.orElse(settings.recentMinutes()));
        reader.boundStatements(STATEMENT_TIMEOUT_MILLIS);
        if (!reader.deviceExists(device)) return AnomalyReport.notFound(device, now);
        Instant from = now.minus(Duration.ofHours(s.windowHours()));
        Instant split = now.minus(Duration.ofMinutes(s.recentMinutes()));
        List<String> capabilities;
        boolean listTruncated = false;
        if (capability.isPresent()) {
            capabilities = List.of(capability.get());
        } else {
            capabilities = new ArrayList<>(reader.capabilities(device, from, now, s.maxCapabilities() + 1));
            if (capabilities.size() > s.maxCapabilities()) {
                capabilities = new ArrayList<>(capabilities.subList(0, s.maxCapabilities()));
                listTruncated = true;
            }
        }
        List<Finding> findings = new ArrayList<>();
        for (String key : capabilities) {
            TelemetrySeriesReader.Read read = reader.read(device, key, from, now, s.maxSamples());
            // when the read limit cut the window, the window really covered starts at the oldest reading that was read
            Instant seriesFrom = read.truncated() && !read.rows().isEmpty() ? read.rows().getFirst().at() : from;
            CleanedSeries series = SeriesCleaner.clean(read.rows(), now, s.skewToleranceSeconds(), read.truncated());
            Finding.Window window = new Finding.Window(seriesFrom, split.isBefore(seriesFrom) ? seriesFrom : split, now);
            findings.addAll(Detectors.analyse(device, key, series, window, now, s));
        }
        return new AnomalyReport(device, true, now, new Finding.Window(from, split, now), capabilities, listTruncated, reader.connectivity(device).orElse(null), findings);
    }
}
