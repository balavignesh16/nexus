package com.nexus.telemetry.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record TelemetryRequest(
        @NotNull(message = "Device ID is required")
        UUID deviceId,

        @NotNull(message = "Timestamp is required")
        Instant timestamp,

        @NotBlank(message = "Sensor type is required")
        String sensorType,

        @NotNull(message = "Value is required")
        Double value,

        @NotBlank(message = "Unit is required")
        String unit
) {
}
