package com.nexus.twin.api.dto;

import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.TwinStateQuality;
import com.nexus.twin.domain.TwinStateQualityEvidence;
import com.nexus.twin.domain.DesiredTwinState;
import com.nexus.device.capability.definition.CapabilityValueType;
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

    @Test
    void stateQualityIsAdditiveAndDoesNotReplaceLegacyAgeQuality() {
        UUID deviceId = UUID.randomUUID();
        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        DigitalTwin twin = new DigitalTwin(deviceId, old, "TEMPERATURE", 22.5, "CELSIUS", old, Map.of());
        TwinStateQualityEvidence evidence = new TwinStateQualityEvidence(TwinStateQuality.GOOD,
                "WITHIN_FRESHNESS_THRESHOLD", old, old, 120, "ONLINE");

        DigitalTwinResponse response = DigitalTwinResponse.from(List.of(twin), List.of(), Map.of(),
                Map.of(twin.stateKey(), evidence));

        assertEquals("OFFLINE", response.quality());
        assertEquals("OFFLINE", response.reportedState().get("TEMPERATURE").quality());
        assertEquals(TwinStateQuality.GOOD,
                response.reportedState().get("TEMPERATURE").stateQuality().state());
    }

    @Test
    void desiredOnlyEffectiveStateCarriesExplicitUnknownQuality() {
        UUID deviceId=UUID.randomUUID();
        UUID bindingId=UUID.randomUUID();
        DesiredTwinState desired=new DesiredTwinState(deviceId,"binding:"+bindingId,bindingId,null,"",
                UUID.randomUUID(),"relay",CapabilityValueType.BOOLEAN,true,null,"MANUAL_COMMAND",
                UUID.randomUUID(),Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2026-01-01T00:00:01Z"));
        TwinStateQualityEvidence unknown=new TwinStateQualityEvidence(TwinStateQuality.UNKNOWN,
                "NO_REPORTED_OBSERVATION",Instant.parse("2026-01-01T00:00:02Z"),null,120,"ONLINE");

        DigitalTwinResponse response=DigitalTwinResponse.from(List.of(),List.of(desired),Map.of(),
                Map.of("binding:"+bindingId,unknown));

        assertEquals(TwinStateQuality.UNKNOWN,response.effectiveState().get("binding:"+bindingId).stateQuality().state());
        assertEquals("NO_REPORTED_OBSERVATION",response.effectiveState().get("binding:"+bindingId).stateQuality().reason());
    }
}
