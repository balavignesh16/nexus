package com.nexus.event.application;

import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.TwinUpdatedEvent;
import com.nexus.event.domain.EventType;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EventDetectorTypedTest {
    @Test void nonnumericObservationProducesEventsWithoutNumericDeltaOrCoercion() {
        EventDetector detector = new EventDetector(mock(EventDispatcher.class));
        for (var type : List.of(CapabilityValueType.BOOLEAN, CapabilityValueType.STRING,
                CapabilityValueType.ENUM, CapabilityValueType.OBJECT)) {
            Object value = switch(type) {
                case BOOLEAN -> true; case STRING -> "ready"; case ENUM -> "AUTO";
                case OBJECT -> Map.of("state", true); default -> throw new AssertionError();
            };
            UUID device = UUID.randomUUID();
            Instant time = Instant.now();
            DigitalTwin twin = new DigitalTwin(device, time, "generic", value, null, time, Map.of(),
                    UUID.randomUUID(), UUID.randomUUID(), "generic", type, null);
            var events = detector.detectTransitions(new TwinUpdatedEvent(device, time, null, twin, "corr"));
            assertEquals(2, events.size());
            assertTrue(events.stream().noneMatch(e -> e.eventType() == EventType.SENSOR_VALUE_CHANGED));
            assertEquals(value, events.getFirst().payload().get("value"));
        }
    }
}
