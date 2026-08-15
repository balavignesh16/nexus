package com.nexus.realtime;

/**
 * A single property delta from a Digital Twin update.
 *
 * Instead of rebroadcasting the entire twin state on each telemetry tick,
 * the backend computes what changed and sends only the diff.
 * This significantly reduces WebSocket bandwidth at scale.
 *
 * Future: migrate to RFC 6902 JSON Patch format.
 *
 * @param property   The twin property that changed (e.g. "temperature", "humidity").
 * @param oldValue   Previous value (null if property is new).
 * @param newValue   Updated value.
 * @param timestamp  Epoch milliseconds when the change occurred.
 */
public record RealtimeDelta(
        String property,
        Object oldValue,
        Object newValue,
        long   timestamp
) {}
