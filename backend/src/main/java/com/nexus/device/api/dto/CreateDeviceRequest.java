package com.nexus.device.api.dto;

import com.nexus.device.domain.DeviceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateDeviceRequest(
    @NotBlank @Size(max = 100) String name,
    @NotNull DeviceType deviceType,
    @NotBlank @Size(max = 100) String manufacturer,
    @NotBlank @Size(max = 100) String model,
    @NotBlank @Size(max = 100) String serialNumber,
    @Size(max = 500) String description
) {}
