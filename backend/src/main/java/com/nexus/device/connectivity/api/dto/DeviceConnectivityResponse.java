package com.nexus.device.connectivity.api.dto;

import com.nexus.device.connectivity.DeviceConnectivity;
import com.nexus.device.connectivity.DeviceConnectivityStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Truthful current connectivity for one device. `status` is "UNKNOWN" when
 * no status message has ever been observed for this device (no fabricated
 * default) - see DeviceConnectivityRegistry's own contract.
 */
public record DeviceConnectivityResponse(
    UUID deviceId,
    String status,
    Instant lastChangedAt
) {
    public static DeviceConnectivityResponse unknown(UUID deviceId) {
        return new DeviceConnectivityResponse(deviceId, "UNKNOWN", null);
    }

    public static DeviceConnectivityResponse from(DeviceConnectivity connectivity) {
        return new DeviceConnectivityResponse(
                connectivity.deviceId(),
                connectivity.status().name(),
                connectivity.lastChangedAt()
        );
    }
}
