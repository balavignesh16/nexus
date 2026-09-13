package com.nexus.analytics.application;

import com.nexus.analytics.api.dto.AnalyticsSummaryResponse;
import com.nexus.analytics.api.dto.TimeSeriesResponse;
import com.nexus.analytics.domain.AnalyticsBucket;
import com.nexus.analytics.domain.AnalyticsMetric;
import com.nexus.analytics.domain.AnalyticsRange;
import com.nexus.analytics.domain.AnalyticsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * All Analytics business logic (Frontend 2.0 Phase 2) - resolves the
 * requested range/bucket into concrete bounds, delegates every actual
 * aggregation to AnalyticsRepository, and applies the one piece of
 * cross-cutting truthfulness logic that doesn't belong in raw SQL: the
 * pipeline metric's window is clamped to its real 7-day retention, and
 * the response reports the window actually queried, never the nominal
 * requested one.
 */
@Service
public class AnalyticsService {

    private static final Duration PIPELINE_RETENTION = Duration.ofDays(7);

    private final AnalyticsRepository repository;
    private final Clock clock;

    @Autowired
    public AnalyticsService(AnalyticsRepository repository) {
        this(repository, Clock.systemUTC());
    }

    // Visible for testing: allows a fixed "now" so tests are deterministic.
    AnalyticsService(AnalyticsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public AnalyticsSummaryResponse summary(String rangeParam) {
        AnalyticsRange range = AnalyticsRange.fromParam(rangeParam);
        Instant to = Instant.now(clock);
        Instant from = range.from(to);

        return new AnalyticsSummaryResponse(
                rangeParam,
                from,
                to,
                repository.totalDeviceCount(),
                repository.deviceCountByType(),
                repository.connectivityDistribution(),
                repository.eventCountInRange(from, to),
                repository.ruleMatchCountInRange(from, to),
                repository.actionStatusDistributionInRange(from, to),
                repository.commandAckDistributionInRange(from, to)
        );
    }

    public TimeSeriesResponse timeseries(String metricParam, String rangeParam, String bucketParam) {
        AnalyticsMetric metric = AnalyticsMetric.fromParam(metricParam);
        AnalyticsRange range = AnalyticsRange.fromParam(rangeParam);
        AnalyticsBucket bucket = bucketParam != null ? AnalyticsBucket.fromParam(bucketParam) : range.defaultBucket();
        AnalyticsBucket.validateForRange(bucket, range);

        Instant to = Instant.now(clock);
        Instant from = range.from(to);

        // Pipeline traces retain only 7 days - never imply more history
        // exists than actually does. The clamped window is what's
        // reported back, not the nominal requested one.
        if (metric == AnalyticsMetric.PIPELINE) {
            Instant pipelineFloor = to.minus(PIPELINE_RETENTION);
            if (from.isBefore(pipelineFloor)) {
                from = pipelineFloor;
            }
        }

        List<AnalyticsRepository.TimeSeriesRow> rows = switch (metric) {
            case TELEMETRY -> repository.telemetryTimeSeries(from, to, bucket.intervalLiteral());
            case EVENTS -> repository.eventsTimeSeries(from, to, bucket.postgresUnit());
            case RULE_MATCHES -> repository.ruleMatchesTimeSeries(from, to, bucket.postgresUnit());
            case ACTIONS -> repository.actionsTimeSeries(from, to, bucket.postgresUnit());
            case PIPELINE -> repository.pipelineTimeSeries(from, to, bucket.intervalLiteral());
        };

        List<TimeSeriesResponse.Point> points = rows.stream()
                .map(r -> new TimeSeriesResponse.Point(r.bucket(), r.count()))
                .toList();

        return new TimeSeriesResponse(metricParam, from, to, bucket.name(), points);
    }
}
