package com.nexus.device.api.dto;

import com.nexus.device.domain.DeviceMode;
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
    @Size(max = 500) String description,
    // Phase 11: deliberately NOT @NotNull - existing callers (the Python
    // simulator, any pre-Phase-11 client) that omit this field must keep
    // working unmodified. DeviceService resolves a null mode to SIMULATED.
    DeviceMode mode
) {}
