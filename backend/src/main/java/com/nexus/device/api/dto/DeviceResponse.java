package com.nexus.device.api.dto;

import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;

import java.time.Instant;
import java.util.UUID;

public record DeviceResponse(
    UUID id,
    UUID spaceId,
    String name,
    DeviceType deviceType,
    String manufacturer,
    String model,
    String serialNumber,
    DeviceStatus status,
    String description,
    Instant createdAt,
    Instant updatedAt,
    String createdBy,
    String updatedBy
) {
    public static DeviceResponse from(Device device) {
        return new DeviceResponse(
            device.getId(),
            device.getSpace().getId(),
            device.getName(),
            device.getDeviceType(),
            device.getManufacturer(),
            device.getModel(),
            device.getSerialNumber(),
            device.getStatus(),
            device.getDescription(),
            device.getCreatedAt(),
            device.getUpdatedAt(),
            device.getCreatedBy(),
            device.getUpdatedBy()
        );
    }
}
