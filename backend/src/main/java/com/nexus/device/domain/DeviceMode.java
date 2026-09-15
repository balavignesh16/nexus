package com.nexus.device.domain;

/**
 * Whether a device's telemetry originates from the demo simulator or real
 * hardware (Tier 6 / Phase 11). Provenance/display metadata only - nothing
 * in the ingestion, twin, rule, or action pipeline branches on this value.
 * Immutable after creation: a device changing from simulated to real
 * represents a different physical registration, not an edit.
 */
public enum DeviceMode {
    SIMULATED,
    REAL
}
