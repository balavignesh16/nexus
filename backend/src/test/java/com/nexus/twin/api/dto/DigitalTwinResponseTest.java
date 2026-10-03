package com.nexus.twin.api.dto;

import com.nexus.twin.domain.DigitalTwin;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DigitalTwinResponseTest {

    @Test
    void from_multipleCapabilities_preservesCompleteReportedStateAndLatestSummary() {
        UUID deviceId = UUID.randomUUID();
        Instant earlier = Instant.parse("2026-01-01T00:00:00Z");
        Instant later = Instant.parse("2026-01-01T00:00:05Z");
        DigitalTwin temperature = new DigitalTwin(deviceId, earlier, "TEMPERATURE", 22.5, "CELSIUS", earlier, Map.of("source", "bme280"));
        DigitalTwin humidity = new DigitalTwin(deviceId, later, "HUMIDITY", 55.0, "PERCENT", later, Map.of());

        DigitalTwinResponse response = DigitalTwinResponse.from(List.of(humidity, temperature));

        assertEquals(deviceId, response.deviceId());
        assertEquals("HUMIDITY", response.latestSensorType());
        assertEquals(55.0, response.latestValue());
        assertEquals(List.of("HUMIDITY", "TEMPERATURE"), response.reportedState().keySet().stream().toList());
        assertEquals(22.5, response.reportedState().get("TEMPERATURE").value());
        assertEquals("CELSIUS", response.reportedState().get("TEMPERATURE").unit());
        assertEquals(earlier, response.reportedState().get("TEMPERATURE").timestamp());
        assertEquals("bme280", response.reportedState().get("TEMPERATURE").metadata().get("source"));
    }

    @Test
    void from_emptyCapabilityList_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> DigitalTwinResponse.from(List.of()));
    }
}
