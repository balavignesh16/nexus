package com.nexus.device.binding.api;

import java.util.Map;
import java.util.UUID;

public record BindingMutationRequest(UUID capabilityDefinitionId, Boolean enabled,
        String unit, Map<String,Object> configuration, Map<String,Object> externalIdentifiers) {}
