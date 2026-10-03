package com.nexus.device.template.api;
import com.nexus.device.template.DeviceTemplateCapability;
import java.util.*;
public record TemplateCapabilityResponse(UUID id,UUID capabilityDefinitionId,String capabilityKey,boolean required,String defaultUnit,
 int displayOrder,Integer freshnessThresholdSeconds,Map<String,Object> configuration){
 public static TemplateCapabilityResponse from(DeviceTemplateCapability c){return new TemplateCapabilityResponse(c.getId(),c.getDefinition().getId(),c.getDefinition().getKey(),c.isRequired(),c.getDefaultUnit(),c.getDisplayOrder(),c.getFreshnessThresholdSeconds(),c.getConfiguration());}
}
