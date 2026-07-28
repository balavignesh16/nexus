package com.nexus.device.api;

import com.nexus.device.api.dto.CreateDeviceRequest;
import com.nexus.device.api.dto.DeviceResponse;
import com.nexus.device.api.dto.UpdateDeviceRequest;
import com.nexus.device.application.DeviceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping("/spaces/{spaceId}/devices")
    public List<DeviceResponse> getDevicesBySpaceId(@PathVariable UUID spaceId) {
        return deviceService.getDevicesBySpaceId(spaceId);
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
}
