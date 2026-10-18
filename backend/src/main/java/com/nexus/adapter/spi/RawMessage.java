package com.nexus.adapter.spi;

import java.util.Map;
import java.util.Objects;

/**
 * Bytes and transport metadata exactly as received. {@code topic} is the MQTT topic (null for transports without one) and
 * {@code metadata} carries transport headers such as the declared contract version. The payload is copied on the way in and out.
 */
public record RawMessage(byte[] payload, String topic, Map<String, String> metadata) {
    public RawMessage {
        payload = Objects.requireNonNull(payload, "payload").clone();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public RawMessage(byte[] payload, String topic) {
        this(payload, topic, Map.of());
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /** Never prints payload content. */
    @Override
    public String toString() {
        return "RawMessage[" + payload.length + " bytes]";
    }
}
