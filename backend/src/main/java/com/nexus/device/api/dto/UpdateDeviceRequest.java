package com.nexus.device.api.dto;

import com.nexus.device.domain.DeviceStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateDeviceRequest(
    @NotBlank @Size(max = 100) String name,
    @NotNull DeviceStatus status,
    @Size(max = 500) String description
) {}
