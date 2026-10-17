package com.nexus.telemetry.application;

/**
 * A telemetry sample that is deterministically invalid: no amount of redelivery can make it valid (unknown capability, value of
 * the wrong type, violated binding constraint, ...). It is an IllegalArgumentException so existing HTTP handling (400) is
 * unchanged; the MQTT listener acknowledges and drops exactly this type (and a few other explicit ones), and never an arbitrary
 * IllegalArgumentException from deeper layers (decision A1). {@code reason} is a short stable code and is safe as a metric tag
 * because it is drawn from fixed enumerations, never from payload content.
 */
public class InvalidTelemetryException extends IllegalArgumentException {
    private final String reason;

    public InvalidTelemetryException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
