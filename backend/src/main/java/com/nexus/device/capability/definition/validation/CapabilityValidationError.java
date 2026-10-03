package com.nexus.device.capability.definition.validation;

import java.util.Map;

public record CapabilityValidationError(
        CapabilityValidationCode code,
        String message,
        String capabilityKey,
        Map<String, Object> expected,
        Map<String, Object> received
) {}
