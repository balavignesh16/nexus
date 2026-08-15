package com.nexus.telemetry.application;

import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.api.dto.TelemetryResponse;
import com.nexus.telemetry.domain.TelemetryRecord;
import com.nexus.telemetry.domain.TelemetryRepository;
import com.nexus.twin.application.DigitalTwinService;
import com.nexus.pipeline.domain.PipelineTrace;
import com.nexus.pipeline.domain.PipelineStage;
import com.nexus.pipeline.application.PipelineService;
import com.nexus.realtime.RealtimePublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TelemetryService {

    private final TelemetryRepository telemetryRepository;
    private final DeviceRepository deviceRepository;
    private final DigitalTwinService digitalTwinService;
    private final RealtimePublisher realtimePublisher;
    private final PipelineService pipelineService;

    public TelemetryService(TelemetryRepository telemetryRepository, DeviceRepository deviceRepository,
                            DigitalTwinService digitalTwinService, RealtimePublisher realtimePublisher,
                            PipelineService pipelineService) {
        this.telemetryRepository = telemetryRepository;
        this.deviceRepository = deviceRepository;
        this.digitalTwinService = digitalTwinService;
        this.realtimePublisher = realtimePublisher;
        this.pipelineService = pipelineService;
    }

    public TelemetryResponse processTelemetry(TelemetryRequest request) {
        Device device = deviceRepository.findById(request.deviceId())
                .orElseThrow(() -> new com.nexus.shared.exception.ResourceNotFoundException("Device", request.deviceId().toString()));

        if (device.getStatus() != DeviceStatus.ACTIVE) {
            throw new IllegalStateException("Cannot ingest telemetry for inactive device");
        }

        String correlationId = UUID.randomUUID().toString();
        long stageStart = System.currentTimeMillis();

        TelemetryRecord record = new TelemetryRecord(
                UUID.randomUUID(),
                request.deviceId(),
                request.timestamp(),
                request.sensorType(),
                request.value(),
                request.unit()
        );

        telemetryRepository.save(record);

        // Broadcast telemetry in real time
        realtimePublisher.publishTelemetry(record, correlationId);

        // Pipeline trace for F7.5 visualization
        pipelineService.recordTrace(
                PipelineTrace.of(correlationId, request.deviceId().toString(), PipelineStage.TELEMETRY, stageStart, "SUCCESS",
                        request, java.util.Map.of("sensorType", request.sensorType(), "value", request.value())));

        // Update Digital Twin
        digitalTwinService.updateTwin(request, correlationId);

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
