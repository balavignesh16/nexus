package com.nexus.analytics.api;

import com.nexus.analytics.api.dto.AnalyticsSummaryResponse;
import com.nexus.analytics.api.dto.TimeSeriesResponse;
import com.nexus.analytics.application.AnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only NEXUS Operational Analytics (Frontend 2.0 Phase 2). Every
 * value returned is a real database aggregate over persisted data -
 * never a fabricated/mock value. Invalid range/metric/bucket parameters
 * are rejected as IllegalArgumentException, mapped to 400 by the
 * existing GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/summary")
    public AnalyticsSummaryResponse summary(@RequestParam String range) {
        return analyticsService.summary(range);
    }

    @GetMapping("/timeseries")
    public TimeSeriesResponse timeseries(@RequestParam String metric,
                                          @RequestParam String range,
                                          @RequestParam(required = false) String bucket) {
        return analyticsService.timeseries(metric, range, bucket);
    }
}
