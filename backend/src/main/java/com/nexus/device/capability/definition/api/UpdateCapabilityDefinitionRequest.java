package com.nexus.device.capability.definition.api;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record UpdateCapabilityDefinitionRequest(
        @NotBlank String displayName,
        String description,
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
