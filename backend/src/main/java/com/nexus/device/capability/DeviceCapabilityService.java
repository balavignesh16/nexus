package com.nexus.device.capability;

import com.nexus.device.capability.api.dto.CreateDeviceCapabilityRequest;
import com.nexus.device.capability.api.dto.DeviceCapabilityResponse;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.exception.DeviceNotFoundException;
import com.nexus.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * NEXUS-authoritative device capability provisioning (Tier 5C). Capabilities
 * are never created, inferred, or modified by telemetry ingestion, MQTT
 * connectivity handling, or firmware self-declaration - only through this
 * service, backed by the device-capability API.
 */
@Service
public class DeviceCapabilityService {
    private com.nexus.rule.application.ActiveRuleReferenceGuard activeVersions;
    @org.springframework.beans.factory.annotation.Autowired
    void setActiveVersions(com.nexus.rule.application.ActiveRuleReferenceGuard guard){this.activeVersions=guard;}

    private final DeviceCapabilityRepository capabilityRepository;
    private final DeviceRepository deviceRepository;

    public DeviceCapabilityService(DeviceCapabilityRepository capabilityRepository, DeviceRepository deviceRepository) {
        this.capabilityRepository = capabilityRepository;
        this.deviceRepository = deviceRepository;
    }

    @Transactional(readOnly = true)
    public List<DeviceCapabilityResponse> getCapabilities(UUID deviceId) {
        requireDeviceExists(deviceId);
        return capabilityRepository.findByDeviceId(deviceId).stream()
                .map(DeviceCapabilityResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public DeviceCapabilityResponse addCapability(UUID deviceId, CreateDeviceCapabilityRequest request) {
        requireDeviceExists(deviceId);

        if (capabilityRepository.existsByDeviceIdAndCapabilityTypeAndDirection(deviceId, request.capabilityType(), request.direction())) {
            throw new DeviceCapabilityExistsException(deviceId, request.capabilityType(), request.direction());
        }

        DeviceCapability capability = new DeviceCapability(
                UUID.randomUUID(),
                deviceId,
                request.capabilityType(),
                request.direction(),
                request.unit(),
                request.metadata()
        );

        return DeviceCapabilityResponse.from(capabilityRepository.save(capability));
    }

    @Transactional
    public void deleteCapability(UUID deviceId, UUID capabilityId) {
        if(activeVersions!=null) {
            deviceRepository.findByIdForUpdate(deviceId).orElseThrow(()->new DeviceNotFoundException(deviceId));
        }
        // Ownership-scoped lookup (both id AND deviceId must match) - a
        // capability belonging to a different device is simply not found,
        // never deleted, regardless of which device's URL is used.
        DeviceCapability capability = capabilityRepository.findByIdAndDeviceId(capabilityId, deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceCapability", capabilityId.toString()));
        if(activeVersions!=null)activeVersions.reject(deviceId,com.nexus.device.capability.definition.LegacyCapabilityDefinitionMapping.keyFor(capability.getCapabilityType()));
        capabilityRepository.delete(capability);
    }

    /**
     * Batch lookup for collection endpoints (Tier 5C) - a single query
     * covering every requested device, avoiding an N+1 query pattern.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<DeviceCapabilityResponse>> getCapabilitiesForDevices(Collection<UUID> deviceIds) {
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        return capabilityRepository.findByDeviceIdIn(deviceIds).stream()
                .collect(Collectors.groupingBy(
                        DeviceCapability::getDeviceId,
                        Collectors.mapping(DeviceCapabilityResponse::from, Collectors.toList())));
    }

    private void requireDeviceExists(UUID deviceId) {
        if (!deviceRepository.existsById(deviceId)) {
            throw new DeviceNotFoundException(deviceId);
        }
    }
}
