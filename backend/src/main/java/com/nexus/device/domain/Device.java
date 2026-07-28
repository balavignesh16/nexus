package com.nexus.device.domain;

import com.nexus.space.domain.Space;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "devices")
public class Device {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id", nullable = false)
    private Space space;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false, length = 50)
    private DeviceType deviceType;

    @Column(nullable = false, length = 100)
    private String manufacturer;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "serial_number", nullable = false, length = 100, unique = true)
    private String serialNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private DeviceStatus status;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    protected Device() {}

    public Device(UUID id, Space space, String name, DeviceType deviceType, String manufacturer, String model, String serialNumber, String description) {
        this.id = id;
        this.space = space;
        this.name = name;
        this.deviceType = deviceType;
        this.manufacturer = manufacturer;
        this.model = model;
        this.serialNumber = serialNumber;
        this.status = DeviceStatus.REGISTERED;
        this.description = description;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.createdBy = "system";
        this.updatedBy = "system";
    }

    public UUID getId() { return id; }
    public Space getSpace() { return space; }
    public String getName() { return name; }
    public DeviceType getDeviceType() { return deviceType; }
    public String getManufacturer() { return manufacturer; }
    public String getModel() { return model; }
    public String getSerialNumber() { return serialNumber; }
    public DeviceStatus getStatus() { return status; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }

    public void update(String name, DeviceStatus status, String description) {
        this.name = name;
        this.status = status;
        this.description = description;
        this.updatedAt = Instant.now();
        this.updatedBy = "system";
    }
}
