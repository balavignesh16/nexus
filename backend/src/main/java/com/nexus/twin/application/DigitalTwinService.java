package com.nexus.twin.application;

import com.nexus.pipeline.domain.PipelineTrace;
import com.nexus.pipeline.domain.PipelineStage;
import com.nexus.pipeline.application.PipelineService;
import com.nexus.realtime.RealtimePublisher;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.DigitalTwinRegistry;
import com.nexus.twin.domain.TwinUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class DigitalTwinService {

    private static final Logger log = LoggerFactory.getLogger(DigitalTwinService.class);

    private final DigitalTwinRegistry registry;
    private final ApplicationEventPublisher eventPublisher;
    private final RealtimePublisher realtimePublisher;
    private final PipelineService pipelineService;

    public DigitalTwinService(DigitalTwinRegistry registry, ApplicationEventPublisher eventPublisher,
                              RealtimePublisher realtimePublisher, PipelineService pipelineService) {
        this.registry = registry;
        this.eventPublisher = eventPublisher;
        this.realtimePublisher = realtimePublisher;
        this.pipelineService = pipelineService;
    }

    /**
     * Updates the digital twin if the incoming telemetry is strictly newer
     * than the current twin's state. Broadcasts delta to WebSocket clients.
     *
     * @param request       The incoming telemetry request
     * @param correlationId Links this update to the originating telemetry event
     */
    public void updateTwin(TelemetryRequest request, String correlationId) {
        Optional<DigitalTwin> currentTwinOpt = registry.get(request.deviceId());

        if (currentTwinOpt.isPresent()) {
            DigitalTwin currentTwin = currentTwinOpt.get();
            if (!request.timestamp().isAfter(currentTwin.latestTimestamp())) {
                log.debug("Ignored out-of-order telemetry for device {}. Current: {}, Incoming: {}",
                        request.deviceId(), currentTwin.latestTimestamp(), request.timestamp());
                return;
            }
        }

        Instant now = Instant.now();
        long stageStart = System.currentTimeMillis();

        DigitalTwin newTwin = new DigitalTwin(
                request.deviceId(),
                request.timestamp(),
                request.sensorType(),
                request.value(),
                request.unit(),
                now,
                Map.of()
        );

        registry.put(newTwin);

        // Broadcast delta (only changed fields) to WebSocket clients
        realtimePublisher.publishTwinDelta(currentTwinOpt.orElse(null), newTwin, correlationId);

        // Pipeline trace
        pipelineService.recordTrace(
                PipelineTrace.of(correlationId, request.deviceId().toString(), PipelineStage.TWIN, stageStart, "SUCCESS",
                        newTwin, Map.of("sensorType", request.sensorType(), "value", request.value())));

        TwinUpdatedEvent event = new TwinUpdatedEvent(
                request.deviceId(),
                now,
                currentTwinOpt.orElse(null),
                newTwin,
                correlationId
        );
        eventPublisher.publishEvent(event);
        log.debug("Twin updated for device {}. TwinUpdatedEvent published.", request.deviceId());
    }

    public Optional<DigitalTwin> getTwin(UUID deviceId) {
        return registry.get(deviceId);
    }

    public List<DigitalTwin> getAllTwins() {
        return registry.listAll();
    }
}
