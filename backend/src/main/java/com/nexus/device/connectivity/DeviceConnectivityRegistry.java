package com.nexus.device.connectivity;

import java.util.Optional;
import java.util.UUID;

public interface DeviceConnectivityRegistry {

    /**
     * Records an observed connectivity status for a device. A no-op
     * (returns false, does not update last_changed_at) if the device's
     * currently stored status already equals the observed one - duplicate
     * ONLINE-while-ONLINE or OFFLINE-while-OFFLINE must never appear as a
     * state transition. Returns true only when the stored status actually
     * changed (including the first-ever observation for a device).
     */
    boolean applyObservedStatus(UUID deviceId, DeviceConnectivityStatus status);

    /** Empty means UNKNOWN - this device has never been observed. */
    Optional<DeviceConnectivity> get(UUID deviceId);
}
