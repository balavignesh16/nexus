package com.nexus.device.binding;

import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.definition.CapabilityDefinition;
import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.device.onboarding.DeviceProvisioningService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BindingConstraintServiceTest {
    private final BindingConstraintService service = new BindingConstraintService();

    @Test void numericRangeCanNarrowButNotExpand() {
        var servo = definition("servo", CapabilityValueType.NUMBER, CapabilityDirection.WRITE,
                BigDecimal.ZERO, new BigDecimal("180"), List.of());
        var narrowed = service.narrow(servo, Map.of(), Map.of("minimum", 10, "maximum", 160));
        assertEquals(new BigDecimal("10"), narrowed.get("minimum"));
        assertEquals(new BigDecimal("160"), narrowed.get("maximum"));
        assertThrows(IllegalArgumentException.class, () -> service.narrow(servo, narrowed, Map.of("maximum", 181)));
        assertThrows(IllegalArgumentException.class, () -> service.validateValue(servo, narrowed, 170));
        assertDoesNotThrow(() -> service.validateValue(servo, narrowed, 90));
    }

    @Test void relayChannelsAndEnumValuesCanOnlyShrink() {
        var relay = definition("relay", CapabilityValueType.OBJECT, CapabilityDirection.WRITE, null, null, List.of());
        var channels = service.narrow(relay, Map.of("channels", List.of(1,2,3,4)), Map.of("channels", List.of(1,2)));
        assertEquals(List.of(1,2), channels.get("channels"));
        assertThrows(IllegalArgumentException.class, () -> service.narrow(relay, channels, Map.of("channels", List.of(1,3))));
        assertThrows(IllegalArgumentException.class, () -> service.validateValue(relay, channels, Map.of("channel", 3, "state", true)));
        var color = definition("color", CapabilityValueType.ENUM, CapabilityDirection.READ, null, null, List.of("RED","GREEN"));
        assertThrows(IllegalArgumentException.class, () -> service.narrow(color, Map.of(), Map.of("enumValues", List.of("RED","BLUE"))));
    }

    @Test void invalidUnitAndDirectionShapedOverrideAreRejected() {
        var sensor = definition("soil_moisture", CapabilityValueType.NUMBER, CapabilityDirection.READ,
                BigDecimal.ZERO, new BigDecimal("100"), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.unit(sensor, "CELSIUS"));
        assertThrows(IllegalArgumentException.class, () -> service.narrow(sensor, Map.of(), Map.of("direction", "WRITE")));
        assertThrows(IllegalArgumentException.class, () -> service.narrow(sensor, Map.of(), Map.of("maximum", 200)));
    }

    @Test void externalIdentifiersCannotBeAmbiguousWithinOneDeviceDirection() {
        var first = mock(DeviceCapabilityBinding.class);
        var second = mock(DeviceCapabilityBinding.class);
        when(first.isEnabled()).thenReturn(true);
        when(second.isEnabled()).thenReturn(true);
        when(first.getDefinition()).thenReturn(definition("soil_moisture", CapabilityValueType.NUMBER,
                CapabilityDirection.READ, null, null, List.of()));
        when(second.getDefinition()).thenReturn(definition("humidity", CapabilityValueType.NUMBER,
                CapabilityDirection.READ, null, null, List.of()));
        when(first.getExternalIdentifiers()).thenReturn(Map.of("telemetryKey", "SOIL"));
        when(second.getExternalIdentifiers()).thenReturn(Map.of("telemetryKey", "soil"));
        assertThrows(IllegalArgumentException.class,
                () -> DeviceProvisioningService.assertUnambiguous(List.of(first, second)));
    }

    private CapabilityDefinition definition(String key, CapabilityValueType type, CapabilityDirection direction,
            BigDecimal min, BigDecimal max, List<String> enums) {
        return new CapabilityDefinition(UUID.randomUUID(), key, key, null, direction, type, "PERCENT",
                List.of("PERCENT"), min, max, enums, direction == CapabilityDirection.WRITE
                ? List.of("SET_STATE") : List.of(), null, Map.of(), Map.of());
    }
}
