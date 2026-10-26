package com.nexus.adapter.spi;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A decoded telemetry sample in the vocabulary of the wire contract (adapter-contract.md section 7). {@code value} is a plain JSON
 * value (a number, boolean, string, list or map); type-checking against the capability stays in the core.
 */
public record TelemetryPayload(UUID deviceId, Instant timestamp, String sensorType, Object value, String unit, String capabilityKey,
                               UUID readbackCommandId, String messageId) {
    public TelemetryPayload {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(value, "value");
    }
}
