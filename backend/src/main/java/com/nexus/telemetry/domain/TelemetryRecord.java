package com.nexus.telemetry.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "telemetry_history")
@IdClass(TelemetryRecordId.class)
public class TelemetryRecord {
    @Id
    @Column(name = "id")
    private UUID id;
    
    @Id
    @Column(name = "timestamp")
    private Instant timestamp;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "sensor_type", nullable = false)
    private String sensorType;

    @Column(name = "\"value\"", nullable = false)
    private double value;

    @Column(name = "unit", nullable = false)
    private String unit;

    protected TelemetryRecord() {}

    public TelemetryRecord(UUID id, UUID deviceId, Instant timestamp, String sensorType, double value, String unit) {
        this.id = id;
        this.deviceId = deviceId;
        this.timestamp = timestamp;
        this.sensorType = sensorType;
        this.value = value;
        this.unit = unit;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getSensorType() {
        return sensorType;
    }

    public double getValue() {
        return value;
    }

    public String getUnit() {
        return unit;
    }
}
