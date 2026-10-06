package com.nexus.twin.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class DesiredTwinStateId implements Serializable {
    private UUID deviceId;
    private String targetKey;
    private String subtargetKey;

    public DesiredTwinStateId() {}
    public DesiredTwinStateId(UUID deviceId, String targetKey, String subtargetKey) {
        this.deviceId = deviceId;
        this.targetKey = targetKey;
        this.subtargetKey = subtargetKey;
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof DesiredTwinStateId id)) return false;
        return Objects.equals(deviceId, id.deviceId) && Objects.equals(targetKey, id.targetKey)
                && Objects.equals(subtargetKey, id.subtargetKey);
    }
    @Override public int hashCode() { return Objects.hash(deviceId, targetKey, subtargetKey); }
}
