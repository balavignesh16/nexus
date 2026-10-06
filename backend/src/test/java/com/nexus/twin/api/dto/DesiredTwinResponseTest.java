package com.nexus.twin.api.dto;

import com.nexus.action.domain.*;
import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.twin.domain.DesiredTwinState;
import com.nexus.twin.domain.DigitalTwin;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DesiredTwinResponseTest {
    private final UUID device = UUID.randomUUID();
    private final UUID binding = UUID.randomUUID();
    private final UUID command = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-03T09:00:00Z");

    private DesiredTwinState desired(Object value, CapabilityValueType type) {
        return new DesiredTwinState(device, "binding:" + binding, binding, null, "", null,
                "led", type, value, null, "MANUAL_COMMAND", command, now, now);
    }

    @Test void desiredOnlyNeverMasqueradesAsReported() {
        var response = DigitalTwinResponse.from(List.of(), List.of(desired(true, CapabilityValueType.BOOLEAN)), Map.of());
        assertTrue(response.reportedState().isEmpty());
        assertNull(response.latestValue());
        var effective = response.effectiveState().get("binding:" + binding);
        assertEquals("DESIRED_UNVERIFIED", effective.source());
        assertNull(effective.observedValue());
        assertEquals(true, effective.intentValue());
    }

    @Test void reportedMismatchWinsWithoutClaimingConvergence() {
        var observed = new DigitalTwin(device, now, "LED", false, null, now, Map.of(),
                null, binding, "led", CapabilityValueType.BOOLEAN, null);
        var action = new ActionHistoryRecord(command, null, device, ActionType.SEND_COMMAND,
                ExecutionStatus.SUCCESS, Map.of(), now, "published");
        var response = DigitalTwinResponse.from(List.of(observed),
                List.of(desired(true, CapabilityValueType.BOOLEAN)), Map.of(command, action));
        var effective = response.effectiveState().get("binding:" + binding);
        assertEquals("REPORTED", effective.source());
        assertEquals(false, effective.value());
        assertEquals(true, effective.intentValue());
        assertEquals("SUCCESS", response.desiredState().get("binding:" + binding).deliveryStatus());
    }

    @Test void relayChannelsHaveDifferentStateIds() {
        var one = new DesiredTwinState(device, "binding:" + binding, binding, null, "channel:1", null,
                "relay", CapabilityValueType.OBJECT, Map.of("channel", 1, "state", true), null,
                "MANUAL_COMMAND", command, now, now);
        var two = new DesiredTwinState(device, "binding:" + binding, binding, null, "channel:2", null,
                "relay", CapabilityValueType.OBJECT, Map.of("channel", 2, "state", false), null,
                "MANUAL_COMMAND", UUID.randomUUID(), now, now);
        var response = DigitalTwinResponse.from(List.of(), List.of(one, two), Map.of());
        assertEquals(2, response.desiredState().size());
    }

    @Test void allSupportedTypedValuesSurviveDesiredAndDerivedSerializationModel() {
        Map<CapabilityValueType,Object> values = new java.util.LinkedHashMap<>();
        values.put(CapabilityValueType.NUMBER, 25.5);
        values.put(CapabilityValueType.BOOLEAN, true);
        values.put(CapabilityValueType.STRING, "ready");
        values.put(CapabilityValueType.ENUM, "AUTO");
        values.put(CapabilityValueType.OBJECT, Map.of("channel", 1, "state", true));
        for (var entry : values.entrySet()) {
            var state = desired(entry.getValue(), entry.getKey());
            var response = DigitalTwinResponse.from(List.of(), List.of(state), Map.of());
            var value = response.desiredState().get("binding:" + binding);
            assertEquals(entry.getKey().name(), value.valueType());
            assertEquals(state.typedValue(), response.effectiveState().get("binding:" + binding).value());
        }
    }
}
