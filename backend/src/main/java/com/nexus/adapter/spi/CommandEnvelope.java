package com.nexus.adapter.spi;

import java.util.Objects;
import java.util.UUID;

/**
 * An already-accepted, already-leased command. The payload bytes are the stored bytes: an adapter delivers them verbatim and never
 * rebuilds or refreshes them (Phase 4 R11), so the acceptance-time timestamp inside them is what the device judges.
 */
public record CommandEnvelope(UUID commandId, UUID deviceId, byte[] payload, String payloadSha256, long payloadEpochSeconds, String correlationId) {
    public CommandEnvelope {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(deviceId, "deviceId");
        payload = Objects.requireNonNull(payload, "payload").clone();
        Objects.requireNonNull(payloadSha256, "payloadSha256");
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /** Never prints the payload. */
    @Override
    public String toString() {
        return "CommandEnvelope[" + payload.length + " bytes]";
    }
}
