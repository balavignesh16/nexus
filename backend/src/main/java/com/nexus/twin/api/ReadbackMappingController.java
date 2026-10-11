package com.nexus.twin.api;

import com.nexus.twin.application.ReadbackMappingService;
import com.nexus.twin.domain.DeviceReadbackMapping;
import com.nexus.twin.domain.DeviceReadbackMappingRepository;
import com.nexus.twin.domain.TemplateReadbackMapping;
import com.nexus.twin.domain.TemplateReadbackMappingRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class ReadbackMappingController {
    private final ReadbackMappingService service;
    private final DeviceReadbackMappingRepository devices;
    private final TemplateReadbackMappingRepository templates;
    public ReadbackMappingController(ReadbackMappingService service,DeviceReadbackMappingRepository devices,
            TemplateReadbackMappingRepository templates){this.service=service;this.devices=devices;this.templates=templates;}
    @GetMapping("/devices/{deviceId}/readback-mappings")
    public List<DeviceReadbackMapping> deviceMappings(@PathVariable UUID deviceId){return devices.findByDeviceId(deviceId);}
    @PostMapping("/devices/{deviceId}/readback-mappings")
    public DeviceReadbackMapping addDevice(@PathVariable UUID deviceId,@RequestBody ReadbackMappingService.Policy policy){
        return service.addDevice(deviceId,policy);
    }
    @DeleteMapping("/devices/{deviceId}/readback-mappings/{mappingId}")
    public ResponseEntity<Void> disableDevice(@PathVariable UUID deviceId,@PathVariable UUID mappingId){
        service.disableDevice(deviceId,mappingId);return ResponseEntity.noContent().build();
    }
    @GetMapping("/device-templates/{templateId}/readback-mappings")
    public List<TemplateReadbackMapping> templateMappings(@PathVariable UUID templateId){return templates.findByTemplateId(templateId);}
    @PostMapping("/device-templates/{templateId}/readback-mappings")
    public TemplateReadbackMapping addTemplate(@PathVariable UUID templateId,@RequestBody ReadbackMappingService.Policy policy){
        return service.addTemplate(templateId,policy);
    }
}
