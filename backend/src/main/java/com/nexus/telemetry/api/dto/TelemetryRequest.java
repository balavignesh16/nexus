package com.nexus.telemetry.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

        UUID readbackCommandId,

        /** Optional sender-chosen identity of this message (R17). Present means "deduplicate"; absent means never. */
        @JsonDeserialize(using = StrictMessageIdDeserializer.class)
        @Pattern(regexp = MESSAGE_ID_PATTERN, message = "messageId must be 1 to 64 characters from A-Z a-z 0-9 . _ : -")
        String messageId
) {
    public static final String MESSAGE_ID_PATTERN = "^[A-Za-z0-9._:-]{1,64}$";

    public TelemetryRequest(UUID deviceId,Instant timestamp,String sensorType,Object value,String unit,String capabilityKey,UUID readbackCommandId) {
        this(deviceId,timestamp,sensorType,value,unit,capabilityKey,readbackCommandId,null);
    }
    public TelemetryRequest(UUID deviceId,Instant timestamp,String sensorType,Object value,String unit,String capabilityKey) {
        this(deviceId,timestamp,sensorType,value,unit,capabilityKey,null,null);
    }
    public TelemetryRequest(UUID deviceId, Instant timestamp, String sensorType, Double value, String unit) {
        this(deviceId, timestamp, sensorType, value, unit, null,null,null);
    }
}
