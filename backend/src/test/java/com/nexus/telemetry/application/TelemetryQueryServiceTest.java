package com.nexus.telemetry.application;

import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.telemetry.api.dto.TelemetryQueryResponse;
import com.nexus.telemetry.api.dto.TelemetryResponse;
import com.nexus.telemetry.domain.TelemetryQuery;
import com.nexus.telemetry.domain.TelemetryRecord;
import com.nexus.telemetry.domain.TelemetryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class TelemetryQueryServiceTest {

    @Mock
    private TelemetryRepository telemetryRepository;

    @Mock
    private DeviceRepository deviceRepository;

    private TelemetryQueryService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new TelemetryQueryService(telemetryRepository, deviceRepository);
    }

    @Test
    void shouldQueryTelemetryAndReturnHasMoreTrue() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(createActiveDevice(deviceId)));

        TelemetryRecord r1 = createRecord(deviceId, Instant.now());
        TelemetryRecord r2 = createRecord(deviceId, Instant.now().minusSeconds(1));
        TelemetryRecord r3 = createRecord(deviceId, Instant.now().minusSeconds(2));

        when(telemetryRepository.query(any())).thenReturn(List.of(r1, r2, r3));

        TelemetryQuery query = new TelemetryQuery(deviceId, null, null, 2, "desc");
        TelemetryQueryResponse response = service.queryTelemetry(query);

        assertThat(response.records()).hasSize(2);
        assertThat(response.returnedCount()).isEqualTo(2);
        assertThat(response.hasMore()).isTrue();
    }

    @Test
    void shouldQueryTelemetryAndReturnHasMoreFalse() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(createActiveDevice(deviceId)));

        TelemetryRecord r1 = createRecord(deviceId, Instant.now());
        TelemetryRecord r2 = createRecord(deviceId, Instant.now().minusSeconds(1));

        when(telemetryRepository.query(any())).thenReturn(List.of(r1, r2));

        TelemetryQuery query = new TelemetryQuery(deviceId, null, null, 2, "desc");
        TelemetryQueryResponse response = service.queryTelemetry(query);

        assertThat(response.records()).hasSize(2);
        assertThat(response.returnedCount()).isEqualTo(2);
        assertThat(response.hasMore()).isFalse();
    }

    @Test
    void shouldThrowIfDeviceNotFound() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.empty());

        TelemetryQuery query = new TelemetryQuery(deviceId, null, null, 2, "desc");

        assertThatThrownBy(() -> service.queryTelemetry(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Device not found");
    }

    @Test
    void shouldGetLatestTelemetry() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(createActiveDevice(deviceId)));

        TelemetryRecord r1 = createRecord(deviceId, Instant.now());
        when(telemetryRepository.query(any())).thenReturn(List.of(r1));

        Optional<TelemetryResponse> latest = service.getLatestTelemetry(deviceId);

        assertThat(latest).isPresent();
        assertThat(latest.get().id()).isEqualTo(r1.getId());
    }

    private Device createActiveDevice(UUID deviceId) {
        Device device = new Device(deviceId, null, "Sensor", com.nexus.device.domain.DeviceType.TEMPERATURE_SENSOR, "M", "M", "1", "D");
        device.update("Sensor", com.nexus.device.domain.DeviceStatus.ACTIVE, "D");
        return device;
    }

    private TelemetryRecord createRecord(UUID deviceId, Instant timestamp) {
        return new TelemetryRecord(UUID.randomUUID(), deviceId, timestamp, "TEMPERATURE_SENSOR", 22.0, "CELSIUS");
    }
}
