package com.nexus.analytics.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * GET /api/v1/analytics/timeseries response (Frontend 2.0 Phase 2).
 * `from`/`to` report the ACTUAL window queried - for the `pipeline`
 * metric this may be narrower than the nominally requested range, since
 * pipeline_traces only retains 7 days (nexus.retention.pipeline-days);
 * the frontend must render this actual window, never imply more history
 * exists than the response actually covers.
 */
public record TimeSeriesResponse(
        String metric,
        Instant from,
        Instant to,
        String bucket,
        List<Point> points
) {
    public record Point(Instant timestamp, long value) {}
}
