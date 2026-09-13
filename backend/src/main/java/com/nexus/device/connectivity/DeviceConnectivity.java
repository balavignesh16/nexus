package com.nexus.device.connectivity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable last-known physical MQTT connectivity for one device (Tier 5B).
 * Absence of a row for a given deviceId means UNKNOWN - never observed -
 * which is deliberately not a stored value (see DeviceConnectivityStatus).
 */
@Entity
@Table(name = "device_connectivity")
public class DeviceConnectivity {

    @Id
    @Column(name = "device_id")
    private UUID deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeviceConnectivityStatus status;

    @Column(name = "last_changed_at", nullable = false)
    private Instant lastChangedAt;

    protected DeviceConnectivity() {
    }

    public DeviceConnectivity(UUID deviceId, DeviceConnectivityStatus status, Instant lastChangedAt) {
        this.deviceId = deviceId;
        this.status = status;
        this.lastChangedAt = lastChangedAt;
    }

    public UUID deviceId() {
        return deviceId;
    }

    public DeviceConnectivityStatus status() {
        return status;
    }

    public Instant lastChangedAt() {
        return lastChangedAt;
    }
}
