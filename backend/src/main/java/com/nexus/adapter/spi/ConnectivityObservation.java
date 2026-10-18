package com.nexus.adapter.spi;

import java.util.Objects;
import java.util.UUID;

/**
 * Transport-observed presence delivered to the core. This is an internal value, NOT a wire message: the MQTT wire format is the
 * plain-text status {@code online} / {@code offline} and no JSON connectivity message exists (adapter-contract.md sections 4 and 7).
 */
public record ConnectivityObservation(UUID deviceId, State state) {
    public enum State { ONLINE, OFFLINE }

    public ConnectivityObservation {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(state, "state");
    }
}
