package com.nexus.telemetry.api.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record TelemetryRequest(
        @NotNull(message = "Device ID is required")
        UUID deviceId,

        @NotNull(message = "Timestamp is required")
        Instant timestamp,

        String sensorType,

        @NotNull(message = "Value is required")
        Object value,

        String unit,

        String capabilityKey,

        UUID readbackCommandId
) {
    public TelemetryRequest(UUID deviceId,Instant timestamp,String sensorType,Object value,String unit,String capabilityKey) {
        this(deviceId,timestamp,sensorType,value,unit,capabilityKey,null);
    }
    public TelemetryRequest(UUID deviceId, Instant timestamp, String sensorType, Double value, String unit) {
        this(deviceId, timestamp, sensorType, value, unit, null,null);
    }
}
