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
                    Map.of(
                            "sensorType", current.latestSensorType(),
                            "value", current.latestValue(),
                            "unit", current.latestUnit()
                    ),
                    "INFO"
            ));
        }

        // 2. TWIN_UPDATED (Unconditional on telemetry arrival)
        detectedEvents.add(new DomainEvent(
                UUID.randomUUID(),
                EventType.TWIN_UPDATED,
                twinEvent.deviceId(),
                now,
                "EventDetector",
                Map.of(
                        "sensorType", current.latestSensorType(),
                        "value", current.latestValue(),
                        "unit", current.latestUnit()
                ),
                "INFO"
        ));

        // 3. SENSOR_VALUE_CHANGED
        if (previous != null) {
            double diff = Math.abs(current.latestValue() - previous.latestValue());
            if (diff >= VALUE_CHANGE_THRESHOLD) {
                detectedEvents.add(new DomainEvent(
                        UUID.randomUUID(),
                        EventType.SENSOR_VALUE_CHANGED,
                        twinEvent.deviceId(),
                        now,
                        "EventDetector",
                        Map.of(
                                "sensorType", current.latestSensorType(),
                                "previousValue", previous.latestValue(),
                                "currentValue", current.latestValue(),
                                "unit", current.latestUnit()
                        ),
                        "INFO"
                ));
            }
        }

        return detectedEvents;
    }
}
