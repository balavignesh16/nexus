package com.nexus.device.capability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/**
 * A durable, NEXUS-authoritative fact about what a specific device is
 * provisioned to measure or control (Tier 5C). Never created, inferred, or
 * modified by telemetry ingestion, MQTT connectivity handling, or firmware
 * self-declaration - capabilities are provisioned only through the
 * device-capability API, backed by an authenticated/trusted-network caller,
 * exactly like Device itself.
 */
@Entity
@Table(name = "device_capabilities")
public class DeviceCapability {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability_type", nullable = false, length = 50)
    private CapabilityType capabilityType;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private CapabilityDirection direction;

    @Column(name = "unit", length = 50)
    private String unit;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    protected DeviceCapability() {
    }

    public DeviceCapability(UUID id, UUID deviceId, CapabilityType capabilityType,
                             CapabilityDirection direction, String unit, Map<String, Object> metadata) {
        this.id = id;
        this.deviceId = deviceId;
        this.capabilityType = capabilityType;
        this.direction = direction;
        this.unit = unit;
        this.metadata = metadata;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public CapabilityType getCapabilityType() {
        return capabilityType;
    }

    public CapabilityDirection getDirection() {
        return direction;
    }

    public String getUnit() {
        return unit;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
