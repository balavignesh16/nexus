package com.nexus.device.capability.api.dto;

import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.CapabilityType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CreateDeviceCapabilityRequest(
    @NotNull CapabilityType capabilityType,
    @NotNull CapabilityDirection direction,
    @Size(max = 50) String unit,
    Map<String, Object> metadata
) {}
