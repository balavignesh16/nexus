package com.nexus.device.api;

import com.nexus.device.api.dto.CreateDeviceRequest;
import com.nexus.device.api.dto.DeviceResponse;
import com.nexus.device.api.dto.UpdateDeviceRequest;
import com.nexus.device.application.DeviceService;
import com.nexus.device.capability.DeviceCapabilityService;
import com.nexus.device.capability.api.dto.CreateDeviceCapabilityRequest;
import com.nexus.device.capability.api.dto.DeviceCapabilityResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DeviceController {

    private final DeviceService deviceService;
    private final DeviceCapabilityService deviceCapabilityService;

    public DeviceController(DeviceService deviceService, DeviceCapabilityService deviceCapabilityService) {
        this.deviceService = deviceService;
        this.deviceCapabilityService = deviceCapabilityService;
    }

    @GetMapping("/spaces/{spaceId}/devices")
    public List<DeviceResponse> getDevicesBySpaceId(@PathVariable UUID spaceId) {
        return deviceService.getDevicesBySpaceId(spaceId);
    }

    @GetMapping("/devices/active")
    public List<DeviceResponse> getActiveDevices() {
        return deviceService.getActiveDevices();
    }

    @GetMapping("/devices")
    public List<DeviceResponse> getAllDevices() {
        return deviceService.getAllDevices();
    }

    @GetMapping("/devices/{id}")
    public DeviceResponse getDevice(@PathVariable UUID id) {
        return deviceService.getDevice(id);
    }

    @PostMapping("/spaces/{spaceId}/devices")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceResponse createDevice(
            @PathVariable UUID spaceId,
            @Valid @RequestBody CreateDeviceRequest request
    ) {
        return deviceService.createDevice(spaceId, request);
    }

    @PutMapping("/devices/{id}")
    public DeviceResponse updateDevice(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDeviceRequest request
    ) {
        return deviceService.updateDevice(id, request);
    }

    @DeleteMapping("/devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDevice(@PathVariable UUID id) {
        deviceService.deleteDevice(id);
    }

    // ---------- Device capabilities (Tier 5C) ----------

    @GetMapping("/devices/{deviceId}/capabilities")
    public List<DeviceCapabilityResponse> getDeviceCapabilities(@PathVariable UUID deviceId) {
        return deviceCapabilityService.getCapabilities(deviceId);
    }

    @PostMapping("/devices/{deviceId}/capabilities")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceCapabilityResponse addDeviceCapability(
            @PathVariable UUID deviceId,
            @Valid @RequestBody CreateDeviceCapabilityRequest request
    ) {
        return deviceCapabilityService.addCapability(deviceId, request);
    }

    @DeleteMapping("/devices/{deviceId}/capabilities/{capabilityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDeviceCapability(@PathVariable UUID deviceId, @PathVariable UUID capabilityId) {
        deviceCapabilityService.deleteCapability(deviceId, capabilityId);
    }
}
