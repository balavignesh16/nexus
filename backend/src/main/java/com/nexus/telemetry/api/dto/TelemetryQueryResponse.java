package com.nexus.telemetry.api.dto;

import java.util.List;

public record TelemetryQueryResponse(
        List<TelemetryResponse> records,
        int returnedCount,
        boolean hasMore
) {
}
