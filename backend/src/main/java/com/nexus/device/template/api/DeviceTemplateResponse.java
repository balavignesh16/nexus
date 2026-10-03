package com.nexus.device.template.api;
import com.nexus.device.template.*;
import java.time.Instant; import java.util.*;
public record DeviceTemplateResponse(UUID id,String key,String displayName,String description,String manufacturer,String model,
 ProtocolType protocolType,boolean enabled,long version,boolean compositionLocked,Map<String,Object> metadata,Instant createdAt,Instant updatedAt) {
 public static DeviceTemplateResponse from(DeviceTemplate t){return from(t,false);}
 public static DeviceTemplateResponse from(DeviceTemplate t,boolean locked){return new DeviceTemplateResponse(t.getId(),t.getKey(),t.getDisplayName(),t.getDescription(),t.getManufacturer(),t.getModel(),t.getProtocolType(),t.isEnabled(),t.getVersion(),locked,t.getMetadata(),t.getCreatedAt(),t.getUpdatedAt());}
}
