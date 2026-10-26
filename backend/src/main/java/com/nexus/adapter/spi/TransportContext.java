package com.nexus.adapter.spi;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Where a decoded message came from. {@code deviceId} is the authenticated device (HTTP) or the device derived from the topic (MQTT);
 * it is never taken from the payload. {@code receivedAt} is the backend clock. {@code sourceLabel} is diagnostic only.
 */
public record TransportContext(String adapterId, TransportKind transport, UUID deviceId, Instant receivedAt, String sourceLabel) {
    public TransportContext {
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
