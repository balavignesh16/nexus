package com.nexus.device.capability;

import com.nexus.device.capability.api.dto.CreateDeviceCapabilityRequest;
import com.nexus.device.capability.api.dto.DeviceCapabilityResponse;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.exception.DeviceNotFoundException;
import com.nexus.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceCapabilityServiceTest {

    @Mock
    private DeviceCapabilityRepository capabilityRepository;

    @Mock
    private DeviceRepository deviceRepository;

    private DeviceCapabilityService service;

    @BeforeEach
    void setUp() {
        service = new DeviceCapabilityService(capabilityRepository, deviceRepository);
    }

    // ---------- getCapabilities ----------

    @Test
    void getCapabilities_unknownDevice_throwsDeviceNotFoundException() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.existsById(deviceId)).thenReturn(false);

        assertThatThrownBy(() -> service.getCapabilities(deviceId))
                .isInstanceOf(DeviceNotFoundException.class);
    }

    @Test
    void getCapabilities_knownDevice_returnsMappedList() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        DeviceCapability capability = new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.TEMPERATURE, CapabilityDirection.READ, "CELSIUS", null);
        when(capabilityRepository.findByDeviceId(deviceId)).thenReturn(List.of(capability));

        List<DeviceCapabilityResponse> result = service.getCapabilities(deviceId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).capabilityType()).isEqualTo(CapabilityType.TEMPERATURE);
    }

    @Test
    void getCapabilities_noneConfigured_returnsEmptyList_notNull() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(capabilityRepository.findByDeviceId(deviceId)).thenReturn(List.of());

        List<DeviceCapabilityResponse> result = service.getCapabilities(deviceId);

        assertThat(result).isNotNull().isEmpty();
    }

    // ---------- addCapability ----------

    @Test
    void addCapability_unknownDevice_throwsDeviceNotFoundException() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.existsById(deviceId)).thenReturn(false);
        CreateDeviceCapabilityRequest request = new CreateDeviceCapabilityRequest(CapabilityType.TEMPERATURE, CapabilityDirection.READ, "CELSIUS", null);

        assertThatThrownBy(() -> service.addCapability(deviceId, request))
                .isInstanceOf(DeviceNotFoundException.class);
        verify(capabilityRepository, never()).save(any());
    }

    @Test
    void addCapability_duplicateTypeAndDirection_throwsDeviceCapabilityExistsException() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(capabilityRepository.existsByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.TEMPERATURE, CapabilityDirection.READ))
                .thenReturn(true);
        CreateDeviceCapabilityRequest request = new CreateDeviceCapabilityRequest(CapabilityType.TEMPERATURE, CapabilityDirection.READ, "CELSIUS", null);

        assertThatThrownBy(() -> service.addCapability(deviceId, request))
                .isInstanceOf(DeviceCapabilityExistsException.class);
        verify(capabilityRepository, never()).save(any());
    }

    @Test
    void addCapability_sameTypeDifferentDirection_isAllowed() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(capabilityRepository.existsByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(false);
        when(capabilityRepository.save(any(DeviceCapability.class))).thenAnswer(inv -> inv.getArgument(0));
        CreateDeviceCapabilityRequest request = new CreateDeviceCapabilityRequest(CapabilityType.LED, CapabilityDirection.WRITE, null, null);

        DeviceCapabilityResponse response = service.addCapability(deviceId, request);

        assertThat(response.capabilityType()).isEqualTo(CapabilityType.LED);
        assertThat(response.direction()).isEqualTo(CapabilityDirection.WRITE);
    }

    @Test
    void addCapability_valid_persistsAndReturnsResponse() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(capabilityRepository.existsByDeviceIdAndCapabilityTypeAndDirection(any(), any(), any())).thenReturn(false);
        when(capabilityRepository.save(any(DeviceCapability.class))).thenAnswer(inv -> inv.getArgument(0));
        CreateDeviceCapabilityRequest request = new CreateDeviceCapabilityRequest(CapabilityType.HUMIDITY, CapabilityDirection.READ, "PERCENT", Map.of("precision", 1));

        DeviceCapabilityResponse response = service.addCapability(deviceId, request);

        assertThat(response.deviceId()).isEqualTo(deviceId);
        assertThat(response.capabilityType()).isEqualTo(CapabilityType.HUMIDITY);
        assertThat(response.direction()).isEqualTo(CapabilityDirection.READ);
        assertThat(response.unit()).isEqualTo("PERCENT");
        assertThat(response.metadata()).containsEntry("precision", 1);
    }

    // ---------- deleteCapability (ownership-scoped) ----------

    @Test
    void deleteCapability_belongsToDevice_deletes() {
        UUID deviceId = UUID.randomUUID();
        UUID capabilityId = UUID.randomUUID();
        DeviceCapability capability = new DeviceCapability(capabilityId, deviceId, CapabilityType.TEMPERATURE, CapabilityDirection.READ, "CELSIUS", null);
        when(capabilityRepository.findByIdAndDeviceId(capabilityId, deviceId)).thenReturn(Optional.of(capability));

        service.deleteCapability(deviceId, capabilityId);

        verify(capabilityRepository).delete(capability);
    }

    @Test
    void deleteCapability_belongsToDifferentDevice_isNotFound_andNeverDeleted() {
        // The critical ownership check: findByIdAndDeviceId is scoped by
        // BOTH id and deviceId - a capability that exists but belongs to a
        // different device is simply not found through this device's URL,
        // and must never be deleted.
        UUID requestedDeviceId = UUID.randomUUID();
        UUID capabilityId = UUID.randomUUID();
        when(capabilityRepository.findByIdAndDeviceId(capabilityId, requestedDeviceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteCapability(requestedDeviceId, capabilityId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(capabilityRepository, never()).delete(any(DeviceCapability.class));
        verify(capabilityRepository, never()).deleteById(any());
    }

    @Test
    void deleteCapability_unknownCapabilityId_throwsResourceNotFoundException() {
        UUID deviceId = UUID.randomUUID();
        UUID capabilityId = UUID.randomUUID();
        when(capabilityRepository.findByIdAndDeviceId(capabilityId, deviceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteCapability(deviceId, capabilityId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- batch retrieval (N+1 avoidance) ----------

    @Test
    void getCapabilitiesForDevices_emptyInput_returnsEmptyMap_noRepositoryCall() {
        Map<UUID, List<DeviceCapabilityResponse>> result = service.getCapabilitiesForDevices(List.of());

        assertThat(result).isEmpty();
        verify(capabilityRepository, never()).findByDeviceIdIn(any());
    }

    @Test
    void getCapabilitiesForDevices_multipleDevices_usesSingleBatchedQuery() {
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        DeviceCapability capA = new DeviceCapability(UUID.randomUUID(), deviceA, CapabilityType.TEMPERATURE, CapabilityDirection.READ, "CELSIUS", null);
        DeviceCapability capB = new DeviceCapability(UUID.randomUUID(), deviceB, CapabilityType.MOTION, CapabilityDirection.READ, null, null);
        when(capabilityRepository.findByDeviceIdIn(List.of(deviceA, deviceB))).thenReturn(List.of(capA, capB));

        Map<UUID, List<DeviceCapabilityResponse>> result = service.getCapabilitiesForDevices(List.of(deviceA, deviceB));

        assertThat(result.get(deviceA)).hasSize(1);
        assertThat(result.get(deviceB)).hasSize(1);
        // Exactly one call to the batched repository method - never one per device.
        verify(capabilityRepository, times(1)).findByDeviceIdIn(any());
        verify(capabilityRepository, never()).findByDeviceId(any());
    }
}
