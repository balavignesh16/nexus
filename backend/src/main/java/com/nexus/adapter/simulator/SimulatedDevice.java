package com.nexus.adapter.simulator;

import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.CommandEnvelope;

import java.util.Optional;
import java.util.UUID;

/**
 * A device that lives inside the backend process (a test fixture, a demonstration, a conformance reference). It is NOT a model of any
 * physical device: whatever it does, it proves nothing about hardware.
 */
public interface SimulatedDevice {
    UUID deviceId();

    boolean running();

    /**
     * Receives one command exactly as the adapter was handed it (the stored bytes) and returns the acknowledgement the device would send, or
     * nothing when the bytes are not a command it can correlate (like the firmware, which then sends no acknowledgement).
     */
    Optional<AckPayload> onCommand(CommandEnvelope command);
}
