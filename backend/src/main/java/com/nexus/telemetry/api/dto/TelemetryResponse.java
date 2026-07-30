package com.nexus.telemetry.api.dto;

import com.nexus.telemetry.domain.TelemetryRecord;

import java.time.Instant;
import java.util.UUID;

public record TelemetryResponse(
        UUID id,
        UUID deviceId,
        Instant timestamp,
        String sensorType,
        double value,
        String unit
) {
    public static TelemetryResponse from(TelemetryRecord record) {
        return new TelemetryResponse(
                record.getId(),
                record.getDeviceId(),
                record.getTimestamp(),
                record.getSensorType(),
                record.getValue(),
                record.getUnit()
        );
    }
}
