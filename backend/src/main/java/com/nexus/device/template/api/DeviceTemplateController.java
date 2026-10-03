package com.nexus.device.template.api;
import com.nexus.device.template.DeviceTemplateService;
import jakarta.validation.Valid;
import org.springframework.http.*; import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/device-templates")
public class DeviceTemplateController {
 private final DeviceTemplateService service; public DeviceTemplateController(DeviceTemplateService s){service=s;}
 @GetMapping public List<DeviceTemplateResponse> all(){return service.all();}
 @GetMapping("/{id}") public DeviceTemplateResponse get(@PathVariable UUID id){return service.get(id);}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) public DeviceTemplateResponse create(@Valid @RequestBody DeviceTemplateRequest r){return service.create(r);}
 @PutMapping("/{id}") public DeviceTemplateResponse update(@PathVariable UUID id,@Valid @RequestBody UpdateDeviceTemplateRequest r){return service.update(id,r);}
 @GetMapping("/{id}/capabilities") public List<TemplateCapabilityResponse> capabilities(@PathVariable UUID id){return service.capabilities(id);}
 @PostMapping("/{id}/capabilities") @ResponseStatus(HttpStatus.CREATED) public TemplateCapabilityResponse add(@PathVariable UUID id,@Valid @RequestBody TemplateCapabilityRequest r){return service.addCapability(id,r);}
 @PutMapping("/{id}/capabilities/{associationId}") public TemplateCapabilityResponse updateCapability(@PathVariable UUID id,@PathVariable UUID associationId,@Valid @RequestBody TemplateCapabilityRequest r){return service.updateCapability(id,associationId,r);}
 @DeleteMapping("/{id}/capabilities/{associationId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id,@PathVariable UUID associationId){service.removeCapability(id,associationId);}
}
