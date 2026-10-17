package com.nexus.telemetry.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexus.telemetry.domain.TelemetryRecord;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of accepting one telemetry sample. {@code duplicate} is an additive field (R17, decision A5): it is present and
 * true only when the submission repeated an already accepted messageId; it is omitted otherwise, so every existing
 * response keeps its exact shape. For a duplicate, {@code id} is the original sample's id when that sample is still stored
 * and null otherwise; the reference is never invented.
 */
public record TelemetryResponse(
        UUID id,
        UUID deviceId,
        Instant timestamp,
        String sensorType,
        Double value,
        String unit,
        String capabilityKey,
        String valueType,
        Object typedValue,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean duplicate
) {
    public TelemetryResponse(UUID id, UUID deviceId, Instant timestamp, String sensorType, Double value, String unit,
                             String capabilityKey, String valueType, Object typedValue) {
        this(id, deviceId, timestamp, sensorType, value, unit, capabilityKey, valueType, typedValue, null);
    }

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

    public TelemetryResponse asDuplicate() {
        return new TelemetryResponse(id, deviceId, timestamp, sensorType, value, unit, capabilityKey, valueType, typedValue, Boolean.TRUE);
    }

    public boolean repeatsAcceptedMessage() {
        return Boolean.TRUE.equals(duplicate);
    }
}
