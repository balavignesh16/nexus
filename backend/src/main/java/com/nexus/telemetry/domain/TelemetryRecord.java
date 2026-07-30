package com.nexus.telemetry.domain;

import java.time.Instant;
import java.util.UUID;

public class TelemetryRecord {
    private final UUID id;
    private final UUID deviceId;
    private final Instant timestamp;
    private final String sensorType;
    private final double value;
    private final String unit;

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
