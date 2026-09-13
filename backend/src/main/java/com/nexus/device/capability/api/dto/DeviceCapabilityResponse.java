package com.nexus.device.capability.api.dto;

import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.CapabilityType;
import com.nexus.device.capability.DeviceCapability;

import java.util.Map;
import java.util.UUID;

public record DeviceCapabilityResponse(
    UUID id,
    UUID deviceId,
    CapabilityType capabilityType,
    CapabilityDirection direction,
    String unit,
    Map<String, Object> metadata
) {
    public static DeviceCapabilityResponse from(DeviceCapability capability) {
        return new DeviceCapabilityResponse(
            capability.getId(),
            capability.getDeviceId(),
            capability.getCapabilityType(),
            capability.getDirection(),
            capability.getUnit(),
            capability.getMetadata()
        );
    }
}
