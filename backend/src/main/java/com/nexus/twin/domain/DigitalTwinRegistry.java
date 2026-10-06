package com.nexus.twin.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DigitalTwinRegistry {
    void put(DigitalTwin twin);

    /** Projects already-committed state into the acceleration cache. */
    default void project(DigitalTwin twin) { put(twin); }

    default void evict(UUID deviceId, String stateKey) {}

    default Optional<DigitalTwin> getByStateKey(UUID deviceId, String stateKey) {
        return getAllForDevice(deviceId).stream().filter(t -> stateKey.equals(t.stateKey())).findFirst();
    }

    /**
     * The most-recently-updated capability row for this device (Phase 11).
     * For a device with only one real sensorType (every device today),
     * this is identical to the single row that has always existed. For a
     * multi-capability device, this is the newest-by-latestTimestamp row
     * across its capabilities - preserving the existing "one twin per
     * device" external contract exactly.
     */
    Optional<DigitalTwin> get(UUID deviceId);

    /** Capability-scoped lookup (Phase 11) - the real per-sensorType row. */
    Optional<DigitalTwin> get(UUID deviceId, String sensorType);

    /** Every capability row currently persisted for this device (Phase 11). */
    List<DigitalTwin> getAllForDevice(UUID deviceId);

    void remove(UUID deviceId);

    /**
     * One representative (most-recently-updated) row per device - matches
     * the existing GET /twins list contract exactly, never one row per
     * capability.
     */
    List<DigitalTwin> listAll();
}
