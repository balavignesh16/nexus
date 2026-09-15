package com.nexus.twin.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "digital_twins")
@IdClass(DigitalTwinId.class)
public class DigitalTwin {

    @Id
    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "latest_timestamp", nullable = false)
    private Instant latestTimestamp;

    // Phase 11: part of the composite identity (deviceId, latestSensorType)
    // - a device reporting multiple real sensor types now retains one
    // persisted row per sensorType, instead of the newest overwriting all
    // others. Always populated (see V13 migration for the non-null proof).
    @Id
    @Column(name = "latest_sensor_type", nullable = false)
    private String latestSensorType;

    @Column(name = "latest_value")
    private Double latestValue;

    @Column(name = "latest_unit")
    private String latestUnit;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    protected DigitalTwin() {}

    public DigitalTwin(UUID deviceId, Instant latestTimestamp, String latestSensorType, Double latestValue, String latestUnit, Instant lastSeen, Map<String, Object> metadata) {
        this.deviceId = deviceId;
        this.latestTimestamp = latestTimestamp;
        this.latestSensorType = latestSensorType;
        this.latestValue = latestValue;
        this.latestUnit = latestUnit;
        this.lastSeen = lastSeen;
        this.metadata = metadata;
    }

    public UUID deviceId() { return deviceId; }
    public Instant latestTimestamp() { return latestTimestamp; }
    public String latestSensorType() { return latestSensorType; }
    public Double latestValue() { return latestValue; }
    public String latestUnit() { return latestUnit; }
    public Instant lastSeen() { return lastSeen; }
    public Map<String, Object> metadata() { return metadata; }

    public UUID getDeviceId() { return deviceId; }
    public Instant getLatestTimestamp() { return latestTimestamp; }
    public String getLatestSensorType() { return latestSensorType; }
    public Double getLatestValue() { return latestValue; }
    public String getLatestUnit() { return latestUnit; }
    public Instant getLastSeen() { return lastSeen; }
    public Map<String, Object> getMetadata() { return metadata; }
}
