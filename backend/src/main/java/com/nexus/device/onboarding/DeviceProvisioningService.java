package com.nexus.device.onboarding;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.device.binding.BindingConstraintService;
import com.nexus.device.binding.DeviceCapabilityBinding;
import com.nexus.device.binding.DeviceCapabilityBindingRepository;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.domain.*;
import com.nexus.device.onboarding.api.*;
import com.nexus.device.template.*;
import com.nexus.shared.exception.ResourceNotFoundException;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.twin.application.FreshnessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class DeviceProvisioningService {
    private final DeviceOnboardingSessionRepository sessions;
    private final DeviceTemplateRepository templates;
    private final DeviceTemplateCapabilityRepository templateCapabilities;
    private final DeviceCapabilityBindingRepository bindings;
    private final DeviceRepository devices;
    private final SpaceRepository spaces;
    private final BindingConstraintService constraints;
    private final ObjectMapper canonicalMapper;
    private final Clock clock;
    private final FreshnessPolicy freshness;

    @Autowired
    public DeviceProvisioningService(DeviceOnboardingSessionRepository sessions, DeviceTemplateRepository templates,
            DeviceTemplateCapabilityRepository templateCapabilities, DeviceCapabilityBindingRepository bindings,
            DeviceRepository devices, SpaceRepository spaces, BindingConstraintService constraints,
            ObjectMapper mapper,FreshnessPolicy freshness) {
        this(sessions, templates, templateCapabilities, bindings, devices, spaces, constraints, mapper,
                Clock.systemUTC(),freshness);
    }

    DeviceProvisioningService(DeviceOnboardingSessionRepository sessions, DeviceTemplateRepository templates,
            DeviceTemplateCapabilityRepository templateCapabilities, DeviceCapabilityBindingRepository bindings,
            DeviceRepository devices, SpaceRepository spaces, BindingConstraintService constraints,
            ObjectMapper mapper, Clock clock) {
        this(sessions,templates,templateCapabilities,bindings,devices,spaces,constraints,mapper,clock,new FreshnessPolicy());
    }

    DeviceProvisioningService(DeviceOnboardingSessionRepository sessions, DeviceTemplateRepository templates,
            DeviceTemplateCapabilityRepository templateCapabilities, DeviceCapabilityBindingRepository bindings,
            DeviceRepository devices, SpaceRepository spaces, BindingConstraintService constraints,
            ObjectMapper mapper, Clock clock,FreshnessPolicy freshness) {
        this.sessions=sessions; this.templates=templates; this.templateCapabilities=templateCapabilities;
        this.bindings=bindings; this.devices=devices; this.spaces=spaces; this.constraints=constraints;
        this.canonicalMapper=mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        this.clock=clock;
        this.freshness=freshness;
    }

    @Transactional
    public DeviceOnboardingSessionResponse provision(ProvisionDeviceRequest request) {
        if (request.requestKey() == null || request.requestKey().isBlank()
                || request.requestKey().length() > 150) throw new IllegalArgumentException("Valid requestKey is required");
        String hash = hash(request);
        var prior = sessions.findByRequestKey(request.requestKey());
        if (prior.isPresent()) {
            if (!prior.get().getRequestHash().equals(hash)) throw new OnboardingConflictException("Idempotency key has conflicting body");
            return DeviceOnboardingSessionResponse.from(prior.get());
        }
        // Serialize the first device reference with template-composition policy
        // mutations. Both paths lock this same row before reading capabilities.
        DeviceTemplate template = templates.findByIdForUpdate(request.templateId())
                .orElseThrow(() -> new ResourceNotFoundException("DeviceTemplate", request.templateId().toString()));
        if (!template.isEnabled() || template.getProtocolType() != ProtocolType.MQTT)
            throw new IllegalArgumentException("Template must be enabled and use MQTT");
        var members = templateCapabilities.findByTemplateIdOrderByDisplayOrder(template.getId());
        if (members.isEmpty()) throw new IllegalArgumentException("Template has no capabilities");
        Map<String,BindingOverrideRequest> overrides = request.bindings() == null ? Map.of() : request.bindings();
        Set<String> knownKeys = new HashSet<>();
        for (var member : members) knownKeys.add(member.getDefinition().getKey());
        if (!knownKeys.containsAll(overrides.keySet())) throw new IllegalArgumentException("Override refers to a capability outside the template");
        if (devices.existsBySerialNumber(request.serialNumber())) throw new OnboardingConflictException("Serial number already exists");
        var space = spaces.findById(request.spaceId())
                .orElseThrow(() -> new ResourceNotFoundException("Space", request.spaceId().toString()));

        Instant now = clock.instant();
        Device device = new Device(UUID.randomUUID(), space, request.name(), DeviceType.CUSTOM,
                template.getManufacturer() == null ? "NEXUS" : template.getManufacturer(),
                template.getModel() == null ? template.getKey() : template.getModel(),
                request.serialNumber(), template.getDescription(), request.mode());
        device.attachTemplate(template);
        devices.save(device);

        List<DeviceCapabilityBinding> created = new ArrayList<>();
        for (var member : members) {
            var definition = member.getDefinition();
            var override = overrides.get(definition.getKey());
            boolean enabled = override == null || override.enabled() == null || override.enabled();
            if (member.isRequired() && !enabled) throw new IllegalArgumentException("Required capability cannot be disabled");
            String unit = constraints.unit(definition, override == null || override.unit() == null
                    ? member.getDefaultUnit() : override.unit());
            Map<String,Object> config = constraints.narrow(definition, member.getConfiguration(),
                    override == null ? null : override.configuration());
            Map<String,Object> external = defaultIdentifiers(definition.getKey(), definition.getDirection());
            if (override != null && override.externalIdentifiers() != null) external.putAll(override.externalIdentifiers());
            validateIdentifiers(external);
            Integer freshnessOverride=override==null?null:override.freshnessThresholdSeconds();
            freshness.validateOverride(freshnessOverride,member.getFreshnessThresholdSeconds());
            created.add(new DeviceCapabilityBinding(UUID.randomUUID(), device.getId(), definition, member,
                    enabled, unit, config, external,freshnessOverride,now));
        }
        assertUnambiguous(created);
        bindings.saveAll(created);
        DeviceOnboardingSession session = sessions.save(new DeviceOnboardingSession(UUID.randomUUID(),
                device.getId(), template.getId(), request.requestKey(), hash,
                now.plus(Duration.ofMinutes(5)), now));
        return DeviceOnboardingSessionResponse.from(session);
    }

    public static Map<String,Object> defaultIdentifiers(String key, CapabilityDirection direction) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put(direction == CapabilityDirection.READ ? "telemetryKey" : "commandCapability",
                direction == CapabilityDirection.READ ? key : key.toUpperCase(Locale.ROOT));
        return result;
    }

    public static void validateIdentifiers(Map<String,Object> identifiers) {
        for (var entry : identifiers.entrySet()) {
            if (!Set.of("telemetryKey", "commandCapability").contains(entry.getKey())
                    || !(entry.getValue() instanceof String value) || value.isBlank())
                throw new IllegalArgumentException("Invalid external capability identifier");
        }
    }

    public static void assertUnambiguous(List<DeviceCapabilityBinding> bindings) {
        for (CapabilityDirection direction : CapabilityDirection.values()) {
            Set<String> identities = new HashSet<>();
            for (var binding : bindings) {
                if (!binding.isEnabled() || binding.getDefinition().getDirection() != direction) continue;
                Set<String> own = new HashSet<>();
                own.add(binding.getDefinition().getKey().toLowerCase(Locale.ROOT));
                binding.getExternalIdentifiers().values().forEach(v -> own.add(v.toString().toLowerCase(Locale.ROOT)));
                for (String identity : own) if (!identities.add(identity))
                    throw new IllegalArgumentException("Ambiguous external capability identifier: " + identity);
            }
        }
    }

    private String hash(ProvisionDeviceRequest request) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonicalMapper.writeValueAsBytes(request));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception exception) { throw new IllegalStateException("Cannot fingerprint provisioning request", exception); }
    }
}
