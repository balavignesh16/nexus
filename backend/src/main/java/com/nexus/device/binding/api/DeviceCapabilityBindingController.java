package com.nexus.device.binding.api;
import com.nexus.device.binding.BindingManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*; import java.util.*;
@RestController @RequestMapping("/api/v1/devices/{deviceId}/capability-bindings")
public class DeviceCapabilityBindingController {
 private final BindingManagementService service; public DeviceCapabilityBindingController(BindingManagementService s){service=s;}
 @GetMapping public List<DeviceCapabilityBindingResponse> all(@PathVariable UUID deviceId){return service.all(deviceId);}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) public DeviceCapabilityBindingResponse create(@PathVariable UUID deviceId,@RequestBody BindingMutationRequest request){return service.create(deviceId,request);}
 @PutMapping("/{bindingId}") public DeviceCapabilityBindingResponse update(@PathVariable UUID deviceId,@PathVariable UUID bindingId,@RequestBody BindingMutationRequest request){return service.update(deviceId,bindingId,request);}
}
