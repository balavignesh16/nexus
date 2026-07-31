package com.nexus.telemetry.application;

import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
import com.nexus.space.domain.Space;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.api.dto.TelemetryResponse;
import com.nexus.telemetry.domain.TelemetryRepository;
import com.nexus.twin.application.DigitalTwinService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TelemetryServiceTest {

    private TelemetryRepository telemetryRepository;
    private DeviceRepository deviceRepository;
    private DigitalTwinService digitalTwinService;
    private TelemetryService telemetryService;

    @BeforeEach
    void setUp() {
        telemetryRepository = mock(TelemetryRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        digitalTwinService = mock(DigitalTwinService.class);
        telemetryService = new TelemetryService(telemetryRepository, deviceRepository, digitalTwinService);
    }

    @Test
    void processTelemetry_WhenDeviceActive_SavesAndReturns() {
        UUID deviceId = UUID.randomUUID();
        Device mockDevice = new Device(deviceId, mock(Space.class), "Sensor", DeviceType.TEMPERATURE_SENSOR, "Man", "Mod", "123", "Desc");
        mockDevice.update("Sensor", DeviceStatus.ACTIVE, "Desc"); // Ensure it's active

        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(mockDevice));

        TelemetryRequest request = new TelemetryRequest(deviceId, Instant.now(), "TEMPERATURE_SENSOR", 22.5, "CELSIUS");

        TelemetryResponse response = telemetryService.processTelemetry(request);

        assertNotNull(response);
        assertEquals(22.5, response.value());
        assertEquals(deviceId, response.deviceId());

        verify(telemetryRepository, times(1)).save(any());
        verify(digitalTwinService, times(1)).updateTwin(request);
    }

    @Test
    void processTelemetry_WhenDeviceInactive_ThrowsException() {
        UUID deviceId = UUID.randomUUID();
        Device mockDevice = new Device(deviceId, mock(Space.class), "Sensor", DeviceType.TEMPERATURE_SENSOR, "Man", "Mod", "123", "Desc");
        mockDevice.update("Sensor", DeviceStatus.OFFLINE, "Desc"); // Inactive

        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(mockDevice));

        TelemetryRequest request = new TelemetryRequest(deviceId, Instant.now(), "TEMPERATURE_SENSOR", 22.5, "CELSIUS");

        assertThrows(IllegalStateException.class, () -> telemetryService.processTelemetry(request));
        verify(telemetryRepository, never()).save(any());
    }

    @Test
    void processTelemetry_WhenDeviceNotFound_ThrowsException() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.empty());

        TelemetryRequest request = new TelemetryRequest(deviceId, Instant.now(), "TEMPERATURE_SENSOR", 22.5, "CELSIUS");

        assertThrows(com.nexus.shared.exception.ResourceNotFoundException.class, () -> {
            telemetryService.processTelemetry(request);
        });
        verify(telemetryRepository, never()).save(any());
    }
}
