package com.nexus.device.binding;

import com.nexus.device.binding.api.BindingMutationRequest;
import com.nexus.device.binding.api.DeviceCapabilityBindingResponse;
import com.nexus.device.capability.definition.CapabilityDefinitionRepository;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.onboarding.DeviceProvisioningService;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.persistence.RuleRegistry;
import com.nexus.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class BindingManagementService {
    private final DeviceCapabilityBindingRepository bindings;
    private final CapabilityDefinitionRepository definitions;
    private final DeviceRepository devices;
    private final BindingConstraintService constraints;
    private final RuleRegistry rules;

    public BindingManagementService(DeviceCapabilityBindingRepository bindings,
            CapabilityDefinitionRepository definitions, DeviceRepository devices,
            BindingConstraintService constraints, RuleRegistry rules) {
        this.bindings=bindings; this.definitions=definitions; this.devices=devices;
        this.constraints=constraints; this.rules=rules;
    }

    @Transactional(readOnly = true)
    public List<DeviceCapabilityBindingResponse> all(UUID deviceId) {
        if (!devices.existsById(deviceId)) throw new ResourceNotFoundException("Device", deviceId.toString());
        return bindings.findByDeviceId(deviceId).stream().map(DeviceCapabilityBindingResponse::from).toList();
    }

    @Transactional
    public DeviceCapabilityBindingResponse create(UUID deviceId, BindingMutationRequest request) {
        if (!devices.existsById(deviceId)) throw new ResourceNotFoundException("Device", deviceId.toString());
        if (request.capabilityDefinitionId() == null) throw new IllegalArgumentException("capabilityDefinitionId is required");
        var definition = definitions.findById(request.capabilityDefinitionId())
                .orElseThrow(() -> new ResourceNotFoundException("CapabilityDefinition", request.capabilityDefinitionId().toString()));
        if (bindings.findByDeviceIdAndDefinitionKeyAndEnabledTrue(deviceId, definition.getKey()).isPresent()
                || bindings.findByDeviceId(deviceId).stream().anyMatch(b -> b.getDefinition().getId().equals(definition.getId())))
            throw new IllegalStateException("Capability is already bound to device");
        Map<String,Object> configuration = constraints.narrow(definition, Map.of(), request.configuration());
        String unit = constraints.unit(definition, request.unit());
        Map<String,Object> external = request.externalIdentifiers() == null
                ? DeviceProvisioningService.defaultIdentifiers(definition.getKey(), definition.getDirection())
                : request.externalIdentifiers();
        DeviceProvisioningService.validateIdentifiers(external);
        var binding = new DeviceCapabilityBinding(UUID.randomUUID(), deviceId, definition, null,
                request.enabled() == null || request.enabled(), unit, configuration, external, Instant.now());
        var all = new ArrayList<>(bindings.findByDeviceId(deviceId));
        all.add(binding);
        DeviceProvisioningService.assertUnambiguous(all);
        return DeviceCapabilityBindingResponse.from(bindings.save(binding));
    }

    @Transactional
    public DeviceCapabilityBindingResponse update(UUID deviceId, UUID bindingId, BindingMutationRequest request) {
        var binding = bindings.findById(bindingId)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceCapabilityBinding", bindingId.toString()));
        if (!binding.getDeviceId().equals(deviceId)) throw new ResourceNotFoundException("DeviceCapabilityBinding", bindingId.toString());
        if (request.capabilityDefinitionId() != null && !request.capabilityDefinitionId().equals(binding.getDefinition().getId()))
            throw new IllegalArgumentException("Capability definition identity is immutable");
        boolean enabled = request.enabled() == null ? binding.isEnabled() : request.enabled();
        String unit = constraints.unit(binding.getDefinition(), request.unit() == null ? binding.getUnit() : request.unit());
        Map<String,Object> configuration = request.configuration() == null ? binding.getConfiguration()
                : constraints.narrow(binding.getDefinition(), binding.getConfiguration(), request.configuration());
        Map<String,Object> external = request.externalIdentifiers() == null ? binding.getExternalIdentifiers()
                : request.externalIdentifiers();
        DeviceProvisioningService.validateIdentifiers(external);
        if ((enabled != binding.isEnabled() || !Objects.equals(unit, binding.getUnit())
                || !Objects.equals(configuration, binding.getConfiguration())
                || !Objects.equals(external, binding.getExternalIdentifiers())) && activeRuleReferences(deviceId, binding.getDefinition().getKey()))
            throw new IllegalStateException("Active Rule references this binding");
        binding.update(enabled, unit, configuration, external, Instant.now());
        DeviceProvisioningService.assertUnambiguous(bindings.findByDeviceId(deviceId));
        return DeviceCapabilityBindingResponse.from(binding);
    }

    private boolean activeRuleReferences(UUID deviceId, String key) {
        return rules.listEnabledOrderedByPriority().stream().anyMatch(rule -> {
            boolean deviceScoped = rule.conditions().stream().anyMatch(c -> c.field() == RuleField.DEVICE_ID
                    && deviceId.toString().equals(c.expectedValue()));
            boolean otherDevice = rule.conditions().stream().anyMatch(c -> c.field() == RuleField.DEVICE_ID)
                    && !deviceScoped;
            if (otherDevice) return false;
            boolean condition = rule.conditions().stream().anyMatch(c -> key.equals(c.capabilityKey()));
            boolean action = rule.actions().stream().anyMatch(a -> "SEND_COMMAND".equals(a.actionType())
                    && a.parameters() != null && key.equals(a.parameters().get("capabilityKey"))
                    && deviceId.toString().equals(String.valueOf(a.parameters().get("deviceId"))));
            return condition || action;
        });
    }
}
