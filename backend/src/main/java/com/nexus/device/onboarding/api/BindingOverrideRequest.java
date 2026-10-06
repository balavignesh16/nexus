package com.nexus.device.onboarding.api;

import java.util.Map;

public record BindingOverrideRequest(Boolean enabled, String unit,
        Map<String,Object> configuration, Map<String,Object> externalIdentifiers,
        Integer freshnessThresholdSeconds) {
    public BindingOverrideRequest(Boolean enabled,String unit,Map<String,Object> configuration,
            Map<String,Object> externalIdentifiers){this(enabled,unit,configuration,externalIdentifiers,null);}
}
