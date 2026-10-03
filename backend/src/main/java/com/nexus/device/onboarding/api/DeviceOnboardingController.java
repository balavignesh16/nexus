package com.nexus.device.onboarding.api;

import com.nexus.device.onboarding.DeviceProvisioningService;
import com.nexus.device.onboarding.OnboardingLifecycleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/device-onboarding")
public class DeviceOnboardingController {
    private final DeviceProvisioningService provisioning;
    private final OnboardingLifecycleService lifecycle;

    public DeviceOnboardingController(DeviceProvisioningService provisioning, OnboardingLifecycleService lifecycle) {
        this.provisioning=provisioning; this.lifecycle=lifecycle;
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public DeviceOnboardingSessionResponse provision(@Valid @RequestBody ProvisionDeviceRequest request) {
        return provisioning.provision(request);
    }

    @GetMapping public List<DeviceOnboardingSessionResponse> all() { return lifecycle.all(); }
    @GetMapping("/{sessionId}") public DeviceOnboardingSessionResponse get(@PathVariable UUID sessionId) {
        return lifecycle.get(sessionId);
    }
    @PostMapping("/{sessionId}/retry") public DeviceOnboardingSessionResponse retry(@PathVariable UUID sessionId) {
        return lifecycle.retry(sessionId);
    }
    @PostMapping("/{sessionId}/disable") public DeviceOnboardingSessionResponse disable(@PathVariable UUID sessionId) {
        return lifecycle.disable(sessionId);
    }
}
