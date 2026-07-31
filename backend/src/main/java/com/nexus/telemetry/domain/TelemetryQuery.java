package com.nexus.telemetry.domain;

import java.time.Instant;
import java.util.UUID;

public record TelemetryQuery(
        UUID deviceId,
        Instant before,
        Instant after,
        int limit,
        String sortDirection // "asc" or "desc"
) {
    public TelemetryQuery {
        if (limit <= 0) {
            limit = 50; // default
        }
        if (sortDirection == null || sortDirection.isBlank()) {
            sortDirection = "desc";
        }
    }
}
