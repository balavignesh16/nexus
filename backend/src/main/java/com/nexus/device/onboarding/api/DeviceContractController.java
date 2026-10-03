package com.nexus.device.onboarding.api;

import com.nexus.device.onboarding.DeviceContractService;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/devices/{deviceId}/contract")
public class DeviceContractController {
    private final DeviceContractService service;
    public DeviceContractController(DeviceContractService service) { this.service=service; }
    @GetMapping public DeviceContractResponse get(@PathVariable UUID deviceId) { return service.get(deviceId); }
}
