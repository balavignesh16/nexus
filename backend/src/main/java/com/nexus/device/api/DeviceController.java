package com.nexus.device.api;

import com.nexus.device.api.dto.CreateDeviceRequest;
import com.nexus.device.api.dto.DeviceResponse;
import com.nexus.device.api.dto.UpdateDeviceRequest;
import com.nexus.device.application.DeviceService;
import com.nexus.action.api.dto.ActionHistoryResponse;
import com.nexus.device.capability.DeviceCapabilityService;
import com.nexus.device.capability.api.dto.CreateDeviceCapabilityRequest;
import com.nexus.device.capability.api.dto.DeviceCapabilityResponse;
import com.nexus.device.command.DeviceCommandService;
import com.nexus.device.command.api.dto.CreateDeviceCommandRequest;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.api.dto.DeviceConnectivityResponse;
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
    private final DeviceCommandService deviceCommandService;
    private final DeviceConnectivityRegistry deviceConnectivityRegistry;

    public DeviceController(DeviceService deviceService, DeviceCapabilityService deviceCapabilityService,
                             DeviceCommandService deviceCommandService,
                             DeviceConnectivityRegistry deviceConnectivityRegistry) {
        this.deviceService = deviceService;
        this.deviceCapabilityService = deviceCapabilityService;
        this.deviceCommandService = deviceCommandService;
        this.deviceConnectivityRegistry = deviceConnectivityRegistry;
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

    // ---------- Device commands (Tier 5D) ----------
    //
    // HTTP 201 reflects that the command request was recorded and
    // processed - it does NOT mean the command reached or was obeyed by
    // the device. The response body's own `status` field (SUCCESS/FAILURE)
    // and `message` are the truthful outcome (e.g. "Command published to
    // MQTT" vs "Device is offline"); callers must read that field, never
    // infer success from the HTTP status alone.

    @PostMapping("/devices/{deviceId}/commands")
    @ResponseStatus(HttpStatus.CREATED)
    public ActionHistoryResponse sendDeviceCommand(
            @PathVariable UUID deviceId,
            @Valid @RequestBody CreateDeviceCommandRequest request
    ) {
        return deviceCommandService.dispatchCommand(deviceId, request);
    }

    // ---------- Device connectivity (Tier 5B) ----------
    //
    // Truthful last-known status only - this reads the same durable registry
    // TelemetryMQTTListener/DeviceStatusMessageHandler already populate from
    // LWT/birth messages. It is NOT an active probe (no round-trip ping to
    // the device exists) - "UNKNOWN" means no status has ever been observed,
    // never a fabricated default.

    @GetMapping("/devices/{deviceId}/connectivity")
    public DeviceConnectivityResponse getDeviceConnectivity(@PathVariable UUID deviceId) {
        deviceService.getDevice(deviceId); // throws DeviceNotFoundException if unknown
        return deviceConnectivityRegistry.get(deviceId)
                .map(DeviceConnectivityResponse::from)
                .orElseGet(() -> DeviceConnectivityResponse.unknown(deviceId));
    }
}
