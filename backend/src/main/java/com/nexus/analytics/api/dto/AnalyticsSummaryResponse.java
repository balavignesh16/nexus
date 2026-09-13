package com.nexus.analytics.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * GET /api/v1/analytics/summary response (Frontend 2.0 Phase 2). Every
 * field here is a real database aggregate over the requested [from, to)
 * range - device counts/connectivity are point-in-time (current state),
 * everything else is scoped to the range. No entity is ever leaked
 * directly; this is a purpose-built read DTO.
 */
public record AnalyticsSummaryResponse(
        String range,
        Instant from,
        Instant to,
        long totalDeviceCount,
        Map<String, Long> deviceCountByType,
        Map<String, Long> connectivityDistribution,
        long eventCount,
        long ruleMatchCount,
        Map<String, Long> actionStatusDistribution,
        Map<String, Long> commandAckDistribution
) {}
