package com.nexus.twin.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite identity for a DigitalTwin row (Phase 11): a device's latest
 * reading is now scoped per real sensorType, not per device alone - so a
 * TEMPERATURE reading and a HUMIDITY reading for the same device no longer
 * overwrite each other's persisted latest state. Field names/types must
 * match DigitalTwin's own @Id fields exactly for JPA's @IdClass contract.
 */
public class DigitalTwinId implements Serializable {

    private UUID deviceId;
    private String stateKey;

    public DigitalTwinId() {
    }

    public DigitalTwinId(UUID deviceId, String stateKey) {
        this.deviceId = deviceId;
        this.stateKey = stateKey;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getStateKey() {
        return stateKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DigitalTwinId)) return false;
        DigitalTwinId that = (DigitalTwinId) o;
        return Objects.equals(deviceId, that.deviceId) && Objects.equals(stateKey, that.stateKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, stateKey);
    }
}
