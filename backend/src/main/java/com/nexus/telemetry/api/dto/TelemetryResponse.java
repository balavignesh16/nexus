package com.nexus.telemetry.api.dto;

import com.nexus.telemetry.domain.TelemetryRecord;

import java.time.Instant;
import java.util.UUID;

public record TelemetryResponse(
        UUID id,
        UUID deviceId,
        Instant timestamp,
        String sensorType,
        Double value,
        String unit,
        String capabilityKey,
        String valueType,
        Object typedValue
) {
    public static TelemetryResponse from(TelemetryRecord record) {
        return new TelemetryResponse(
                record.getId(),
                record.getDeviceId(),
                record.getTimestamp(),
                record.getSensorType(),
                record.getValue(),
                record.getUnit(), record.getCapabilityKey(),
                record.getValueType() == null ? "NUMBER" : record.getValueType().name(), record.getTypedValue()
        );
    }
}
