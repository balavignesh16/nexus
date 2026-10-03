package com.nexus.device.template.api;
import com.nexus.device.template.ProtocolType;
import jakarta.validation.constraints.*;
import java.util.Map;
public record DeviceTemplateRequest(@NotBlank String key,@NotBlank String displayName,String description,
 String manufacturer,String model,@NotNull ProtocolType protocolType,Boolean enabled,Map<String,Object> metadata) {}
