package com.nexus.realtime;

import com.nexus.action.domain.ActionExecutionResult;
import com.nexus.event.domain.DomainEvent;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.telemetry.domain.TelemetryRecord;
import com.nexus.twin.domain.DigitalTwin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Central broadcast service for all real-time platform events.
 *
 * All pipeline services inject this and call the appropriate publish method
 * after completing their work. No business logic lives here — only translation
 * to RealtimeMessage and broadcasting.
 *
 * Thread-safe: SimpMessagingTemplate is thread-safe by design.
 */
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    private final SimpMessagingTemplate messaging;
    private final RealtimeBuffer buffer;
    private final RealtimeMetrics metrics;
    private final AtomicLong sequence = new AtomicLong(0);

    public RealtimePublisher(SimpMessagingTemplate messaging, RealtimeBuffer buffer, RealtimeMetrics metrics) {
        this.messaging = messaging;
        this.buffer = buffer;
        this.metrics = metrics;
    }

    // ─── Telemetry ────────────────────────────────────────────────────────────

    public void publishTelemetry(TelemetryRecord record, String correlationId) {
        Map<String, Object> payload = Map.of(
                "deviceId",   record.getDeviceId().toString(),
                "sensorType", record.getSensorType(),
                "value",      record.getValue(),
                "unit",       record.getUnit(),
                "timestamp",  record.getTimestamp().toEpochMilli()
        );
        broadcast(RealtimeTopics.TELEMETRY, "TelemetryReceived",
                record.getDeviceId().toString(), correlationId, payload);
    }

    // ─── Twin Deltas ──────────────────────────────────────────────────────────

    /**
     * Publishes only the changed properties between old and new twin state.
     * Sends delta patches instead of full object to reduce bandwidth.
     */
    public void publishTwinDelta(DigitalTwin oldTwin, DigitalTwin newTwin, String correlationId) {
        List<RealtimeDelta> deltas = computeDeltas(oldTwin, newTwin);
        if (deltas.isEmpty()) return;

        Map<String, Object> payload = Map.of(
                "deviceId", newTwin.deviceId().toString(),
                "deltas",   deltas,
                "twinTimestamp", newTwin.latestTimestamp().toEpochMilli()
        );
        broadcast(RealtimeTopics.TWINS, "TwinUpdated",
                newTwin.deviceId().toString(), correlationId, payload);
    }

    private List<RealtimeDelta> computeDeltas(DigitalTwin oldTwin, DigitalTwin newTwin) {
        List<RealtimeDelta> deltas = new ArrayList<>();
        long now = System.currentTimeMillis();

        // Compare sensor type
        String oldSensor = oldTwin != null ? oldTwin.latestSensorType() : null;
        if (!newTwin.latestSensorType().equals(oldSensor)) {
            deltas.add(new RealtimeDelta("sensorType", oldSensor, newTwin.latestSensorType(), now));
        }

        // Compare value
        Double oldValue = oldTwin != null ? oldTwin.latestValue() : null;
        if (!newTwin.latestValue().equals(oldValue)) {
            deltas.add(new RealtimeDelta("value", oldValue, newTwin.latestValue(), now));
        }

        return deltas;
    }

    // ─── Domain Events ────────────────────────────────────────────────────────

    public void publishEvent(DomainEvent event, String correlationId) {
        Map<String, Object> payload = Map.of(
                "eventId",   event.eventId().toString(),
                "deviceId",  event.deviceId().toString(),
                "eventType", event.eventType().name(),
                "severity",  event.severity() != null ? event.severity() : "INFO",
                "source",    event.source() != null ? event.source() : "UNKNOWN",
                "timestamp", event.timestamp().toEpochMilli()
        );
        broadcast(RealtimeTopics.EVENTS, "DomainEventCreated",
                event.deviceId().toString(), correlationId, payload);
    }

    // ─── Rule Matches ─────────────────────────────────────────────────────────

    public void publishRuleMatch(RuleEvaluationResult result, String correlationId) {
        if (!result.matched()) return;
        Map<String, Object> payload = Map.of(
                "ruleId",   result.rule().ruleId().toString(),
                "ruleName", result.rule().name(),
                "reason",   result.message(),
                "deviceId", result.event().deviceId().toString()
        );
        broadcast(RealtimeTopics.RULES, "RuleMatched",
                result.event().deviceId().toString(), correlationId, payload);
    }

    // ─── Action Results ───────────────────────────────────────────────────────

    public void publishActionExecuted(ActionExecutionResult result, String correlationId) {
        Map<String, Object> payload = Map.of(
                "executionId", result.executionId().toString(),
                "actionType",  result.request().actionType().name(),
                "status",      result.status().name(),
                "executedAt",  result.timestamp().toEpochMilli(),
                "message",     result.message() != null ? result.message() : ""
        );
        broadcast(RealtimeTopics.ACTIONS, "ActionExecuted",
                null, correlationId, payload);
    }

    // ─── Platform Health ─────────────────────────────────────────────────────

    public void publishPlatformHealth(Map<String, String> status) {
        broadcast(RealtimeTopics.HEALTH, "PlatformHealthChanged", null, null, status);
    }

    // ─── Device Connectivity (Tier 5B) ───────────────────────────────────────

    public void publishDeviceConnectivity(UUID deviceId, String status) {
        Map<String, Object> payload = Map.of(
                "deviceId", deviceId.toString(),
                "status",   status
        );
        // eventType matches the frontend's pre-existing RealtimeEventType
        // union ("DeviceOnline"/"DeviceOffline" were already declared there,
        // anticipating this exact feature) rather than introducing a third,
        // new event name.
        String eventType = "ONLINE".equals(status) ? "DeviceOnline" : "DeviceOffline";
        broadcast(RealtimeTopics.CONNECTIVITY, eventType, deviceId.toString(), null, payload);
    }

    // ─── Pipeline Trace (F7.5 foundation) ────────────────────────────────────

    public void publishPipelineTrace(com.nexus.pipeline.domain.PipelineTrace trace) {
        broadcast(RealtimeTopics.PIPELINE, "PipelineTrace",
                trace.deviceId() != null ? UUID.fromString(trace.deviceId()).toString() : null,
                trace.correlationId(), trace);
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private void broadcast(String topic, String eventType, String deviceId,
                           String correlationId, Object payload) {
        try {
            RealtimeMessage message = RealtimeMessage.of(eventType, deviceId, correlationId, sequence.incrementAndGet(), payload);
            buffer.append(topic, message);
            messaging.convertAndSend(topic, message);
            metrics.recordPublishedMessage();
            log.trace("Broadcast [{}] eventType={} deviceId={}", topic, eventType, deviceId);
        } catch (Exception e) {
            metrics.recordDroppedMessage();
            log.error("Failed to broadcast [{}] eventType={}: {}", topic, eventType, e.getMessage());
        }
    }
}
