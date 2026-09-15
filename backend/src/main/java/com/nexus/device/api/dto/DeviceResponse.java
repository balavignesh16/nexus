package com.nexus.device.api.dto;

import com.nexus.device.capability.api.dto.DeviceCapabilityResponse;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;

import java.time.Instant;
import java.util.List;
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
    DeviceMode mode,
    String description,
    Instant createdAt,
    Instant updatedAt,
    String createdBy,
    String updatedBy,
    List<DeviceCapabilityResponse> capabilities
) {
    public DeviceResponse {
        // Truthful, never-null representation (Tier 5C): a device with no
        // provisioned capabilities exposes an empty list, never null.
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    public static DeviceResponse from(Device device, List<DeviceCapabilityResponse> capabilities) {
        return new DeviceResponse(
            device.getId(),
            device.getSpace().getId(),
            device.getName(),
            device.getDeviceType(),
            device.getManufacturer(),
            device.getModel(),
            device.getSerialNumber(),
            device.getStatus(),
            device.getMode(),
            device.getDescription(),
            device.getCreatedAt(),
            device.getUpdatedAt(),
            device.getCreatedBy(),
            device.getUpdatedBy(),
            capabilities
        );
    }
}
