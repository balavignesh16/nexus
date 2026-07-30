package com.nexus.device.application;

import com.nexus.device.api.dto.CreateDeviceRequest;
import com.nexus.device.api.dto.DeviceResponse;
import com.nexus.device.api.dto.UpdateDeviceRequest;
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
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final SpaceRepository spaceRepository;

    public DeviceService(DeviceRepository deviceRepository, SpaceRepository spaceRepository) {
        this.deviceRepository = deviceRepository;
        this.spaceRepository = spaceRepository;
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> getDevicesBySpaceId(UUID spaceId) {
        if (!spaceRepository.existsById(spaceId)) {
            throw new SpaceNotFoundException(spaceId);
        }
        return deviceRepository.findBySpaceId(spaceId).stream()
                .map(DeviceResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> getActiveDevices() {
        return deviceRepository.findByStatus(com.nexus.device.domain.DeviceStatus.ACTIVE).stream()
                .map(DeviceResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DeviceResponse getDevice(UUID id) {
        return deviceRepository.findById(id)
                .map(DeviceResponse::from)
                .orElseThrow(() -> new DeviceNotFoundException(id));
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
        return DeviceResponse.from(savedDevice);
    }

    @Transactional
    public DeviceResponse updateDevice(UUID id, UpdateDeviceRequest request) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new DeviceNotFoundException(id));

        device.update(request.name(), request.status(), request.description());
        return DeviceResponse.from(deviceRepository.save(device));
    }

    @Transactional
    public void deleteDevice(UUID id) {
        if (!deviceRepository.existsById(id)) {
            throw new DeviceNotFoundException(id);
        }
        deviceRepository.deleteById(id);
    }
}
