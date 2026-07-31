package com.nexus.telemetry.application;

import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.api.dto.TelemetryResponse;
import com.nexus.telemetry.domain.TelemetryRecord;
import com.nexus.telemetry.domain.TelemetryRepository;
import com.nexus.twin.application.DigitalTwinService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TelemetryService {

    private final TelemetryRepository telemetryRepository;
    private final DeviceRepository deviceRepository;
    private final DigitalTwinService digitalTwinService;

    public TelemetryService(TelemetryRepository telemetryRepository, DeviceRepository deviceRepository, DigitalTwinService digitalTwinService) {
        this.telemetryRepository = telemetryRepository;
        this.deviceRepository = deviceRepository;
        this.digitalTwinService = digitalTwinService;
    }

    public TelemetryResponse processTelemetry(TelemetryRequest request) {
        Device device = deviceRepository.findById(request.deviceId())
                .orElseThrow(() -> new IllegalArgumentException("Device not found"));

        if (device.getStatus() != DeviceStatus.ACTIVE) {
            throw new IllegalStateException("Cannot ingest telemetry for inactive device");
        }

        TelemetryRecord record = new TelemetryRecord(
                UUID.randomUUID(),
                request.deviceId(),
                request.timestamp(),
                request.sensorType(),
                request.value(),
                request.unit()
        );

        telemetryRepository.save(record);
        
        // Update Digital Twin
        digitalTwinService.updateTwin(request);

        return TelemetryResponse.from(record);
    }

    public List<TelemetryResponse> getLatestTelemetry(int limit) {
        if (limit <= 0) {
            limit = 100;
        }
        return telemetryRepository.findLatest(limit).stream()
                .map(TelemetryResponse::from)
                .collect(Collectors.toList());
    }
}
