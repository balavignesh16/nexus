package com.nexus.device.onboarding.api;

import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.device.domain.DeviceMode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DeviceContractResponse(String contractVersion, UUID deviceId, UUID templateId,
        Long templateVersion, DeviceMode mode, String protocol, Map<String,String> topics,
        List<ContractCapability> capabilities, Map<String,Object> payloadExamples) {
    public record ContractCapability(UUID bindingId, UUID definitionId, String key, String displayName,
            CapabilityDirection direction, CapabilityValueType valueType, boolean enabled,
            String unit, List<String> allowedUnits, BigDecimal minimum, BigDecimal maximum,
            List<String> enumValues, List<String> supportedCommands,
            String externalTelemetryIdentifier, String externalCommandIdentifier,
            Map<String,Object> effectiveConstraints) {}
}
