package com.nexus.device.onboarding.api;

import com.nexus.device.domain.DeviceMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record ProvisionDeviceRequest(@NotBlank String requestKey, @NotNull UUID templateId,
        @NotNull UUID spaceId, @NotBlank String name, @NotBlank String serialNumber,
        @NotNull DeviceMode mode, Map<String,BindingOverrideRequest> bindings) {}
