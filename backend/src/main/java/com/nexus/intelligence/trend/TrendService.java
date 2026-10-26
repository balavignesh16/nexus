package com.nexus.intelligence.trend;

import com.nexus.intelligence.anomaly.CleanedSeries;
import com.nexus.intelligence.anomaly.SeriesCleaner;
import com.nexus.intelligence.anomaly.TelemetrySeriesReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Trend and rate-of-change analysis of the stored numeric telemetry of one capability of one device. Read-only: one read-only transaction with a statement
 * timeout, a bounded window and a bounded row count, through the same reader the anomaly detectors use; nothing is written, no command is published, no rule is
 * created. The model is not involved. A configured {@link HardLimit} is compared with the newest reading; without one the result says nothing about safety.
 * The window is half-open [from, now) on the DEVICE event-time axis; readings stamped later than the backend instant plus the skew tolerance are excluded and counted.
 */
@Service
public class TrendService {
    static final int STATEMENT_TIMEOUT_MILLIS = 5_000;

    public record Report(UUID deviceId, boolean deviceFound, String capabilityKey, Instant evaluatedAt, TrendResult trend) {}

    private final TelemetrySeriesReader reader;
    private final TrendSettings settings;
    private final Clock clock;

    public TrendService(TelemetrySeriesReader reader, TrendSettings settings, Clock clock) {
        this.reader = reader;
        this.settings = settings;
        this.clock = clock;
    }

    public TrendSettings settings() {
        return settings;
    }

    @Transactional(readOnly = true)
    public Report analyse(UUID device, String capabilityKey, Optional<Integer> windowHours, Optional<HardLimit> limit, Optional<Double> resolution) {
        Instant now = clock.instant();
        reader.boundStatements(STATEMENT_TIMEOUT_MILLIS);
        if (!reader.deviceExists(device)) return new Report(device, false, capabilityKey, now, null);
        int hours = Math.max(1, Math.min(windowHours.orElse(settings.windowHours()), 720));
        Instant from = now.minus(Duration.ofHours(hours));
        TelemetrySeriesReader.Read read = reader.read(device, capabilityKey, from, now, settings.maxSamples());
        CleanedSeries series = SeriesCleaner.clean(read.rows(), now, settings.skewToleranceSeconds(), read.truncated());
        HardLimit hardLimit = limit.orElse(null);
        TrendResult result;
        switch (series.applicability()) {
            case NOT_NUMERIC -> result = TrendAnalyzer.notApplicable("the capability is not a continuous numeric reading (boolean, text or object)", null, from, now, series.quality().asMap(), now);
            case EMPTY -> result = TrendAnalyzer.insufficient("no readings were stored in the window", null, 0, from, now, series.quality().asMap(), hardLimit, null, now);
            case MIXED_UNITS -> result = TrendAnalyzer.insufficient("the readings mix units, so no single series can be described", null, 0, from, now, series.quality().asMap(), hardLimit, null, now);
            case MIXED_VALUE_TYPES -> result = TrendAnalyzer.insufficient("the readings mix value types, so no single series can be described", null, 0, from, now, series.quality().asMap(), hardLimit, null, now);
            default -> {
                if (series.samples().isEmpty())
                    result = TrendAnalyzer.insufficient("no usable reading remained after cleaning (duplicates with different values, future-dated or non-finite readings are removed)", series.unit(), 0, from, now, series.quality().asMap(), hardLimit, null, now);
                else result = TrendAnalyzer.analyse(series.samples(), series.unit(), from, now, series.quality(), hardLimit, resolution.orElse(null), settings, now);
            }
        }
        return new Report(device, true, capabilityKey, now, result);
    }
}
