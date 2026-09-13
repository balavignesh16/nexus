package com.nexus.device.command.api.dto;

import com.nexus.device.capability.CapabilityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateDeviceCommandRequest(
    @NotNull CapabilityType capability,
    @NotBlank String command,
    @NotNull Boolean value
) {}
