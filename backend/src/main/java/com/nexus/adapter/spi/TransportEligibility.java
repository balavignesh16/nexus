package com.nexus.adapter.spi;

import java.time.Instant;
import java.util.UUID;

/**
 * The acceptance-time question "can a command be accepted for this device on its transport?" (adapter-contract.md section 10.8). It
 * replaces the MQTT-shaped checks (connectivity registry ONLINE and broker client connected) that used to be written into command
 * acceptance. The Twin and the Rule engines are not involved. Eligibility is an acceptance signal, not connectivity.
 */
public interface TransportEligibility {
    Eligibility eligible(UUID deviceId, Instant at);
}
