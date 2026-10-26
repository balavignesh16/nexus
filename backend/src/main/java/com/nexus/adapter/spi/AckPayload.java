package com.nexus.adapter.spi;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A decoded command acknowledgement: a device claim. {@code deviceTimestamp} is the device's own clock and is never used for latency. */
public record AckPayload(UUID commandId, Status status, Instant deviceTimestamp, String reason) {
    public enum Status { ACCEPTED, REJECTED }

    public AckPayload {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(deviceTimestamp, "deviceTimestamp");
    }
}
