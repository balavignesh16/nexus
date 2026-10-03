package com.nexus.device.template.api;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
public record UpdateDeviceTemplateRequest(@NotBlank String displayName,String description,String manufacturer,String model,
 boolean enabled,Map<String,Object> metadata) {}
