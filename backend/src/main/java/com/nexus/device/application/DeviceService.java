package com.nexus.device.application;

import com.nexus.device.api.dto.CreateDeviceRequest;
import com.nexus.device.api.dto.DeviceResponse;
import com.nexus.device.api.dto.UpdateDeviceRequest;
import com.nexus.device.capability.DeviceCapabilityService;
import com.nexus.device.capability.api.dto.DeviceCapabilityResponse;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.exception.DeviceNotFoundException;
import com.nexus.device.domain.exception.DeviceSerialNumberExistsException;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.space.application.SpaceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final SpaceRepository spaceRepository;
    private final DeviceCapabilityService deviceCapabilityService;

    public DeviceService(DeviceRepository deviceRepository, SpaceRepository spaceRepository,
                          DeviceCapabilityService deviceCapabilityService) {
        this.deviceRepository = deviceRepository;
        this.spaceRepository = spaceRepository;
        this.deviceCapabilityService = deviceCapabilityService;
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> getDevicesBySpaceId(UUID spaceId) {
        if (!spaceRepository.existsById(spaceId)) {
            throw new SpaceNotFoundException(spaceId);
        }
        List<Device> devices = deviceRepository.findBySpaceId(spaceId);
        return toResponsesBatched(devices);
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> getActiveDevices() {
        List<Device> devices = deviceRepository.findByStatus(com.nexus.device.domain.DeviceStatus.ACTIVE);
        return toResponsesBatched(devices);
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> getAllDevices() {
        List<Device> devices = deviceRepository.findAll();
        return toResponsesBatched(devices);
    }

    @Transactional(readOnly = true)
    public DeviceResponse getDevice(UUID id) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new DeviceNotFoundException(id));
        return DeviceResponse.from(device, deviceCapabilityService.getCapabilities(id));
    }

    @Transactional
    public DeviceResponse createDevice(UUID spaceId, CreateDeviceRequest request) {
        Space space = spaceRepository.findById(spaceId)
                .orElseThrow(() -> new SpaceNotFoundException(spaceId));

        if (deviceRepository.existsBySerialNumber(request.serialNumber())) {
            throw new DeviceSerialNumberExistsException(request.serialNumber());
        }

        Device device = new Device(
                UUID.randomUUID(),
                space,
                request.name(),
                request.deviceType(),
                request.manufacturer(),
                request.model(),
                request.serialNumber(),
                request.description()
        );

        Device savedDevice = deviceRepository.save(device);
        // A newly-created device has no capabilities yet - never fabricated.
        return DeviceResponse.from(savedDevice, List.of());
    }

    @Transactional
    public DeviceResponse updateDevice(UUID id, UpdateDeviceRequest request) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new DeviceNotFoundException(id));

        device.update(request.name(), request.status(), request.description());
        Device savedDevice = deviceRepository.save(device);
        return DeviceResponse.from(savedDevice, deviceCapabilityService.getCapabilities(id));
    }

    @Transactional
    public void deleteDevice(UUID id) {
        if (!deviceRepository.existsById(id)) {
            throw new DeviceNotFoundException(id);
        }
        deviceRepository.deleteById(id);
    }

    /**
     * Maps a list of devices to responses using exactly one batched
     * capability query (Tier 5C) - never one query per device.
     */
    private List<DeviceResponse> toResponsesBatched(List<Device> devices) {
        List<UUID> deviceIds = devices.stream().map(Device::getId).collect(Collectors.toList());
        Map<UUID, List<DeviceCapabilityResponse>> capabilitiesByDevice = deviceCapabilityService.getCapabilitiesForDevices(deviceIds);
        return devices.stream()
                .map(device -> DeviceResponse.from(device, capabilitiesByDevice.getOrDefault(device.getId(), List.of())))
                .collect(Collectors.toList());
    }
}
