package com.nexus.device.capability.definition.api;

import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.definition.CapabilityValueType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record CapabilityDefinitionRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$") String key,
        @NotBlank String displayName,
        String description,
        @NotNull CapabilityDirection direction,
        @NotNull CapabilityValueType valueType,
        String unit,
        List<String> allowedUnits,
        BigDecimal minimum,
        BigDecimal maximum,
        List<String> enumValues,
        List<String> supportedCommands,
        String semanticCategory,
        Map<String, Object> objectConstraints,
        Map<String, Object> metadata
) {}
