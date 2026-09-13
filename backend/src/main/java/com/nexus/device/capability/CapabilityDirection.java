package com.nexus.device.capability;

/**
 * Whether a device capability is observed (READ) or commanded (WRITE)
 * (Tier 5C). WRITE capabilities are metadata only in this phase - no
 * command dispatch, acknowledgement, or actuator execution exists yet.
 */
public enum CapabilityDirection {
    READ,
    WRITE
}
