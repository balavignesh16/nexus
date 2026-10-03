package com.nexus.twin.application;

import com.nexus.pipeline.domain.PipelineTrace;
import com.nexus.pipeline.domain.PipelineStage;
import com.nexus.pipeline.application.PipelineService;
import com.nexus.realtime.RealtimePublisher;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.NormalizedTelemetry;
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
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DigitalTwinService {

    private static final Logger log = LoggerFactory.getLogger(DigitalTwinService.class);

    private final DigitalTwinRegistry registry;
    private final ApplicationEventPublisher eventPublisher;
    private final RealtimePublisher realtimePublisher;
    private final PipelineService pipelineService;

    // Tier 3 Phase 5: per-device lock objects guarding the
    // get -> timestamp-check -> construct -> put sequence in updateTwin()
    // below, so that sequence is atomic per device within this backend
    // instance (see updateTwin()'s own Javadoc for why this is needed).
    //
    // Lifecycle: entries are created once per distinct deviceId ever seen
    // and never removed. This is acceptable at current NEXUS scale because
    // the number of devices is small and bounded by the Site/Building/Space
    // hierarchy in the database - it does not grow with telemetry volume,
    // unlike, say, a per-message or per-request map would. This mirrors
    // DigitalTwinRegistry's own twin cache, which is one entry per
    // device/capability pair and is never evicted either. A distributed lock, an eviction
    // policy, or a lock-striping library would be premature complexity for
    // a single-instance deployment with this device-count profile; if NEXUS
    // is ever horizontally scaled across multiple backend instances, this
    // in-JVM lock stops being sufficient and a database-level mechanism
    // (optimistic locking or a conditional atomic UPDATE) would be required
    // instead - deliberately left as a documented future option, not part
    // of this phase.
    private final ConcurrentHashMap<UUID, Object> deviceLocks = new ConcurrentHashMap<>();

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
     * <p>
     * The read-check-write sequence (read the current twin, decide whether
     * the incoming timestamp is newer, construct the replacement, write it
     * back) is guarded by a per-device lock (Tier 3 Phase 5). Without this,
     * two concurrent updates for the same device (reachable today via
     * concurrent calls to POST /api/v1/telemetry, since Tomcat's request
     * threads are not serialized per device the way Paho's single MQTT
     * callback thread is) could both read the same prior state, both pass
     * the newer-timestamp check against that same stale state, and then
     * write in either order - allowing an older-timestamped update to win
     * over one that was already independently accepted as newer. Only the
     * state decision and state replacement are held under the lock; the
     * downstream event/realtime/pipeline-trace work below intentionally
     * runs after the lock is released, since it does not need to be
     * serialized and does not participate in the race being fixed here.
     *
     * @param request       The incoming telemetry request
     * @param correlationId Links this update to the originating telemetry event
     */
    public void updateTwin(TelemetryRequest request, String correlationId) {
        updateTwin(new NormalizedTelemetry(request.deviceId(),request.timestamp(),request.capabilityKey()!=null?request.capabilityKey():request.sensorType(),request.sensorType(),request.value(),request.unit(),null),correlationId);
    }

    public void updateTwin(NormalizedTelemetry request, String correlationId) {
        UUID deviceId = request.deviceId();
        Object lock = deviceLocks.computeIfAbsent(deviceId, id -> new Object());

        DigitalTwin newTwin;
        DigitalTwin previousTwin;
        Instant now;
        long stageStart;

        synchronized (lock) {
            // Phase 11: out-of-order protection is scoped to the same real
            // sensorType - a newer HUMIDITY reading must never be compared
            // against (or overwrite) an unrelated TEMPERATURE reading's
            // timestamp/state for the same device.
            Optional<DigitalTwin> currentTwinOpt = registry.get(deviceId, request.externalIdentifier());

            if (currentTwinOpt.isPresent()) {
                DigitalTwin currentTwin = currentTwinOpt.get();
                if (!request.timestamp().isAfter(currentTwin.latestTimestamp())) {
                    log.debug("Ignored out-of-order telemetry for device {}. Current: {}, Incoming: {}",
                            deviceId, currentTwin.latestTimestamp(), request.timestamp());
                    return;
                }
            }

            now = Instant.now();
            stageStart = System.currentTimeMillis();

            newTwin = request.resolved()==null ? new DigitalTwin(
                    deviceId,
                    request.timestamp(),
                    request.externalIdentifier(),
                    request.value() instanceof Number n ? n.doubleValue() : null,
                    request.unit(),
                    now,
                    Map.of()
            ) : new DigitalTwin(deviceId,request.timestamp(),request.externalIdentifier(),request.value(),request.unit(),now,Map.of(),
                    request.resolved().definition().getId(),request.resolved().binding()==null?null:request.resolved().binding().getId(),request.capabilityKey(),
                    request.resolved().definition().getValueType(),request.resolved().resolutionSource());

            registry.put(newTwin);
            previousTwin = currentTwinOpt.orElse(null);
        }

        // Broadcast delta (only changed fields) to WebSocket clients
        realtimePublisher.publishTwinDelta(previousTwin, newTwin, correlationId);

        // Pipeline trace
        pipelineService.recordTrace(
                PipelineTrace.of(correlationId, deviceId.toString(), PipelineStage.TWIN, stageStart, "SUCCESS",
                        newTwin, Map.of("sensorType", request.externalIdentifier(), "value", request.value())));

        TwinUpdatedEvent event = new TwinUpdatedEvent(
                deviceId,
                now,
                previousTwin,
                newTwin,
                correlationId
        );
        eventPublisher.publishEvent(event);
        log.debug("Twin updated for device {}. TwinUpdatedEvent published.", deviceId);
    }

    public Optional<DigitalTwin> getTwin(UUID deviceId) {
        return registry.get(deviceId);
    }

    public List<DigitalTwin> getTwinCapabilities(UUID deviceId) {
        return registry.getAllForDevice(deviceId);
    }

    public List<DigitalTwin> getAllTwins() {
        return registry.listAll();
    }
}
