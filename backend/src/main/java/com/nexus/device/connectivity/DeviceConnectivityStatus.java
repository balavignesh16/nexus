package com.nexus.device.connectivity;

/**
 * Physical MQTT connectivity for a device (Tier 5B) - deliberately separate
 * from {@code com.nexus.device.domain.DeviceStatus} (a manually-managed
 * lifecycle field: REGISTERED/ACTIVE/OFFLINE/MAINTENANCE) and from
 * {@code DigitalTwin} (telemetry-observed sensor state). Only ONLINE/OFFLINE
 * are ever persisted - a device that has never published a status message
 * has no {@link DeviceConnectivity} row at all, which callers must treat as
 * UNKNOWN rather than defaulting it to OFFLINE.
 */
public enum DeviceConnectivityStatus {
    ONLINE,
    OFFLINE
}
