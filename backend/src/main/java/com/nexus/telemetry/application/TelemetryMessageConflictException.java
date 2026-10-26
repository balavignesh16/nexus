package com.nexus.telemetry.application;

import java.util.UUID;

/**
 * The same messageId of the same device arrived again with a different payload (R17): the first submission wins, nothing is
 * stored or processed for this one, and it is reported as a conflict (HTTP 409; MQTT acknowledge and drop). It is not an
 * IllegalArgumentException on purpose: it must never be handled as an ordinary validation failure.
 */
public class TelemetryMessageConflictException extends RuntimeException {
    private final UUID deviceId;
    private final String messageId;

    public TelemetryMessageConflictException(UUID deviceId, String messageId) {
        super("messageId " + messageId + " was already used by this device for a different telemetry payload");
        this.deviceId = deviceId;
        this.messageId = messageId;
    }

    public UUID deviceId() { return deviceId; }
    public String messageId() { return messageId; }
}
