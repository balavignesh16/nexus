package com.nexus.device.capability.definition.api;

import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.definition.CapabilityDefinition;
import com.nexus.device.capability.definition.CapabilityValueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CapabilityDefinitionResponse(
        UUID id, String key, String displayName, String description,
        CapabilityDirection direction, CapabilityValueType valueType, String unit,
        List<String> allowedUnits, BigDecimal minimum, BigDecimal maximum,
        List<String> enumValues, List<String> supportedCommands, String semanticCategory,
        Map<String, Object> objectConstraints, Map<String, Object> metadata,
        long version, Instant createdAt, Instant updatedAt
) {
    public static CapabilityDefinitionResponse from(CapabilityDefinition definition) {
        return new CapabilityDefinitionResponse(
                definition.getId(), definition.getKey(), definition.getDisplayName(),
                definition.getDescription(), definition.getDirection(), definition.getValueType(),
                definition.getUnit(), definition.getAllowedUnits(), definition.getMinimum(),
                definition.getMaximum(), definition.getEnumValues(), definition.getSupportedCommands(),
                definition.getSemanticCategory(), definition.getObjectConstraints(),
                definition.getMetadata(), definition.getVersion(), definition.getCreatedAt(),
                definition.getUpdatedAt());
    }
}
