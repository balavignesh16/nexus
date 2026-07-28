package com.nexus.device.application;

import com.nexus.device.api.dto.CreateDeviceRequest;
import com.nexus.device.api.dto.DeviceResponse;
import com.nexus.device.api.dto.UpdateDeviceRequest;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
import com.nexus.device.domain.exception.DeviceNotFoundException;
import com.nexus.device.domain.exception.DeviceSerialNumberExistsException;
import com.nexus.space.application.SpaceNotFoundException;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private SpaceRepository spaceRepository;

    private DeviceService deviceService;

    @BeforeEach
    void setUp() {
        deviceService = new DeviceService(deviceRepository, spaceRepository);
    }

    @Test
    void createDevice_shouldReturnResponse() {
        UUID spaceId = UUID.randomUUID();
        CreateDeviceRequest request = new CreateDeviceRequest("Sensor 1", DeviceType.TEMPERATURE_SENSOR, "Acme", "T-100", "SN-12345", "Desc");
        Space space = mock(Space.class);
        when(space.getId()).thenReturn(spaceId);
        
        when(spaceRepository.findById(spaceId)).thenReturn(Optional.of(space));
        when(deviceRepository.existsBySerialNumber("SN-12345")).thenReturn(false);
        when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));

        DeviceResponse response = deviceService.createDevice(spaceId, request);

        assertThat(response).isNotNull();
        assertThat(response.name()).isEqualTo("Sensor 1");
        assertThat(response.serialNumber()).isEqualTo("SN-12345");
        assertThat(response.createdBy()).isEqualTo("system");
        assertThat(response.updatedBy()).isEqualTo("system");
    }

    @Test
    void createDevice_shouldThrowException_whenSpaceNotFound() {
        UUID spaceId = UUID.randomUUID();
        CreateDeviceRequest request = new CreateDeviceRequest("Sensor 1", DeviceType.TEMPERATURE_SENSOR, "Acme", "T-100", "SN-12345", "Desc");

        when(spaceRepository.findById(spaceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceService.createDevice(spaceId, request))
                .isInstanceOf(SpaceNotFoundException.class);
    }

    @Test
    void createDevice_shouldThrowException_whenSerialNumberExists() {
        UUID spaceId = UUID.randomUUID();
        CreateDeviceRequest request = new CreateDeviceRequest("Sensor 1", DeviceType.TEMPERATURE_SENSOR, "Acme", "T-100", "SN-12345", "Desc");
        Space space = mock(Space.class);
        
        when(spaceRepository.findById(spaceId)).thenReturn(Optional.of(space));
        when(deviceRepository.existsBySerialNumber("SN-12345")).thenReturn(true);

        assertThatThrownBy(() -> deviceService.createDevice(spaceId, request))
                .isInstanceOf(DeviceSerialNumberExistsException.class);
    }

    @Test
    void getDevicesBySpaceId_shouldReturnList() {
        UUID spaceId = UUID.randomUUID();
        Space space = mock(Space.class);
        when(space.getId()).thenReturn(spaceId);
        Device d1 = new Device(UUID.randomUUID(), space, "D1", DeviceType.TEMPERATURE_SENSOR, "Acme", "M1", "SN1", null);
        
        when(spaceRepository.existsById(spaceId)).thenReturn(true);
        when(deviceRepository.findBySpaceId(spaceId)).thenReturn(List.of(d1));

        List<DeviceResponse> responses = deviceService.getDevicesBySpaceId(spaceId);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).name()).isEqualTo("D1");
    }

    @Test
    void getDevicesBySpaceId_shouldThrowException_whenSpaceNotFound() {
        UUID spaceId = UUID.randomUUID();
        when(spaceRepository.existsById(spaceId)).thenReturn(false);

        assertThatThrownBy(() -> deviceService.getDevicesBySpaceId(spaceId))
                .isInstanceOf(SpaceNotFoundException.class);
    }

    @Test
    void getDevice_shouldReturnResponse() {
        UUID id = UUID.randomUUID();
        Space space = mock(Space.class);
        Device device = new Device(id, space, "D1", DeviceType.TEMPERATURE_SENSOR, "Acme", "M1", "SN1", null);
        
        when(deviceRepository.findById(id)).thenReturn(Optional.of(device));

        DeviceResponse response = deviceService.getDevice(id);

        assertThat(response.id()).isEqualTo(id);
    }

    @Test
    void getDevice_shouldThrowException_whenNotFound() {
        UUID id = UUID.randomUUID();
        when(deviceRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceService.getDevice(id))
                .isInstanceOf(DeviceNotFoundException.class);
    }

    @Test
    void updateDevice_shouldUpdateFields() {
        UUID id = UUID.randomUUID();
        Space space = mock(Space.class);
        Device existing = new Device(id, space, "Old", DeviceType.TEMPERATURE_SENSOR, "Acme", "M1", "SN1", null);
        
        when(deviceRepository.findById(id)).thenReturn(Optional.of(existing));
        when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateDeviceRequest request = new UpdateDeviceRequest("New", DeviceStatus.ACTIVE, "New Desc");
        DeviceResponse response = deviceService.updateDevice(id, request);

        assertThat(response.name()).isEqualTo("New");
        assertThat(response.status()).isEqualTo(DeviceStatus.ACTIVE);
        assertThat(response.description()).isEqualTo("New Desc");
        assertThat(response.updatedBy()).isEqualTo("system");
    }

    @Test
    void deleteDevice_shouldCallRepository() {
        UUID id = UUID.randomUUID();
        when(deviceRepository.existsById(id)).thenReturn(true);

        deviceService.deleteDevice(id);

        verify(deviceRepository).deleteById(id);
    }

    @Test
    void deleteDevice_shouldThrowException_whenNotFound() {
        UUID id = UUID.randomUUID();
        when(deviceRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> deviceService.deleteDevice(id))
                .isInstanceOf(DeviceNotFoundException.class);
        verify(deviceRepository, never()).deleteById(any());
    }
}
