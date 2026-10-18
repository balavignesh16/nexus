package com.nexus.adapter.spi;

import java.util.Optional;

/**
 * The server-side transport assignment of a device (decision D3). A device's transport is decided by the platform, never by anything
 * a message says about itself. The names are exactly the values of the V41 CHECK constraint.
 */
public enum TransportKind {
    MQTT_JSON, HTTP_WEBHOOK, SIMULATOR;

    /** Exact, case-sensitive parse; anything else is not a transport. */
    public static Optional<TransportKind> parse(String text) {
        if (text == null) return Optional.empty();
        for (TransportKind kind : values()) {
            if (kind.name().equals(text)) return Optional.of(kind);
        }
        return Optional.empty();
    }
}
