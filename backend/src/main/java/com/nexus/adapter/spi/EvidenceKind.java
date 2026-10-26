package com.nexus.adapter.spi;

/**
 * What a CONFIRMED delivery attempt actually proves (adapter-contract.md section 11). None of these means the device read, accepted or
 * executed the command; each is a statement about what NEXUS observed on its own side of the transport. They are stored as the
 * {@code reason_code} of the PUBLISHED history fact, so the names match {@code ^[A-Z0-9_]{1,64}$}.
 */
public enum EvidenceKind {
    /** The MQTT broker completed QoS 1 publication (existing Phase 4 meaning of PUBLISHED). */
    BROKER_CONFIRMED,
    /** The complete HTTP response was written and flushed to the device's connection by NEXUS; weaker than broker completion. */
    HTTP_SERVED,
    /** An in-process simulated device accepted the call. */
    SIMULATOR_DELIVERED
}
