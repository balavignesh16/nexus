package com.nexus.event.application;

import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.TwinUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class EventDetector {

    private static final Logger log = LoggerFactory.getLogger(EventDetector.class);

    // Delta threshold for SENSOR_VALUE_CHANGED. Could be configurable later.
    private static final double VALUE_CHANGE_THRESHOLD = 0.5;

    private final EventDispatcher eventDispatcher;

    public EventDetector(EventDispatcher eventDispatcher) {
        this.eventDispatcher = eventDispatcher;
    }

    @EventListener
    public void handleTwinUpdated(TwinUpdatedEvent twinEvent) {
        try {
            List<DomainEvent> events = detectTransitions(twinEvent);
            if (!events.isEmpty()) {
                eventDispatcher.publish(events);
            }
        } catch (Exception e) {
            log.error("Failed to detect events for device {}", twinEvent.deviceId(), e);
        }
    }

    protected List<DomainEvent> detectTransitions(TwinUpdatedEvent twinEvent) {
        List<DomainEvent> detectedEvents = new ArrayList<>();
        Instant now = Instant.now();

        DigitalTwin previous = twinEvent.previousState();
        DigitalTwin current = twinEvent.currentState();

        // 1. TWIN_CREATED
        if (previous == null) {
            detectedEvents.add(new DomainEvent(
                    UUID.randomUUID(),
                    EventType.TWIN_CREATED,
                    twinEvent.deviceId(),
                    now,
                    "EventDetector",
                    observationPayload(current),
                    "INFO",
                    twinEvent.correlationId()
            ));
        }

        // 2. TWIN_UPDATED (Unconditional on telemetry arrival)
        detectedEvents.add(new DomainEvent(
                UUID.randomUUID(),
                EventType.TWIN_UPDATED,
                twinEvent.deviceId(),
                now,
                "EventDetector",
                observationPayload(current),
                "INFO",
                twinEvent.correlationId()
        ));

        // 3. SENSOR_VALUE_CHANGED
        if (previous != null && previous.latestValue() != null && current.latestValue() != null) {
            double diff = Math.abs(current.latestValue() - previous.latestValue());
            if (diff >= VALUE_CHANGE_THRESHOLD) {
                detectedEvents.add(new DomainEvent(
                        UUID.randomUUID(),
                        EventType.SENSOR_VALUE_CHANGED,
                        twinEvent.deviceId(),
                        now,
                        "EventDetector",
                        valueChangePayload(previous, current),
                        "INFO",
                        twinEvent.correlationId()
                ));
            }
        }

        return detectedEvents;
    }

    private Map<String,Object> observationPayload(DigitalTwin twin) {
        Map<String,Object> payload = new java.util.LinkedHashMap<>();
        payload.put("sensorType", twin.latestSensorType());
        payload.put("value", twin.typedValue());
        payload.put("unit", twin.latestUnit());
        return payload;
    }

    private Map<String,Object> valueChangePayload(DigitalTwin previous, DigitalTwin current) {
        Map<String,Object> payload = new java.util.LinkedHashMap<>();
        payload.put("sensorType", current.latestSensorType());
        payload.put("previousValue", previous.latestValue());
        payload.put("currentValue", current.latestValue());
        payload.put("unit", current.latestUnit());
        return payload;
    }
}
