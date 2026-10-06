package com.nexus.device.binding.api;
import com.nexus.device.binding.DeviceCapabilityBinding;
import java.util.*;
public record DeviceCapabilityBindingResponse(UUID id,UUID deviceId,UUID capabilityDefinitionId,String capabilityKey,
 UUID templateCapabilityId,boolean enabled,String unit,Map<String,Object> configuration,Map<String,Object> externalIdentifiers,
 Integer freshnessThresholdSeconds){
 public static DeviceCapabilityBindingResponse from(DeviceCapabilityBinding b){return new DeviceCapabilityBindingResponse(b.getId(),b.getDeviceId(),b.getDefinition().getId(),b.getDefinition().getKey(),b.getTemplateCapability()==null?null:b.getTemplateCapability().getId(),b.isEnabled(),b.getUnit(),b.getConfiguration(),b.getExternalIdentifiers(),b.getFreshnessThresholdSeconds());}
}
