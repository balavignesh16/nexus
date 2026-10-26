package com.nexus.adapter.spi;

import java.util.Objects;
import java.util.UUID;

/** The transport-level identity of a sender. {@code credentialReference} is a non-secret lookup identifier, never a key or a hash. */
public record AuthenticatedDevice(UUID deviceId, String credentialReference) {
    public AuthenticatedDevice {
        Objects.requireNonNull(deviceId, "deviceId");
    }

    @Override
    public String toString() {
        return "AuthenticatedDevice[credential " + (credentialReference == null ? "none" : "present") + "]";
    }
}
