package com.nexus.device.capability.definition;

import com.nexus.device.capability.CapabilityDirection;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityDefinitionTest {
    @Test void acceptsValidNumberDefinition() {
        assertDoesNotThrow(() -> number("motor_speed", BigDecimal.ZERO, new BigDecimal("5000")));
    }
    @Test void rejectsInvertedBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> number("motor_speed", BigDecimal.TEN, BigDecimal.ONE));
    }
    @Test void acceptsBooleanStringAndObjectDefinitions() {
        assertDoesNotThrow(() -> definition("switch_state", CapabilityDirection.WRITE,
                CapabilityValueType.BOOLEAN, null, List.of("SET_STATE"), Map.of()));
        assertDoesNotThrow(() -> definition("firmware_version", CapabilityDirection.READ,
                CapabilityValueType.STRING, null, List.of(), Map.of()));
        assertDoesNotThrow(() -> definition("relay_state", CapabilityDirection.WRITE,
                CapabilityValueType.OBJECT, null, List.of("SET_STATE"),
                Map.of("required", List.of("channel", "state"))));
    }
    @Test void enumRequiresValues() {
        assertThrows(IllegalArgumentException.class, () -> definition("mode", CapabilityDirection.READ,
                CapabilityValueType.ENUM, List.of(), List.of(), Map.of()));
        assertDoesNotThrow(() -> definition("mode", CapabilityDirection.READ,
                CapabilityValueType.ENUM, List.of("AUTO", "MANUAL"), List.of(), Map.of()));
    }
    @Test void keyMustAlreadyBeNormalized() {
        assertThrows(IllegalArgumentException.class, () -> number("Motor Speed", null, null));
        assertThrows(IllegalArgumentException.class, () -> number("MOTOR_SPEED", null, null));
    }
    @Test void readCapabilityCannotDeclareCommands() {
        assertThrows(IllegalArgumentException.class, () -> definition("temperature",
                CapabilityDirection.READ, CapabilityValueType.NUMBER, null,
                List.of("SET"), Map.of()));
    }

    private CapabilityDefinition number(String key, BigDecimal min, BigDecimal max) {
        return new CapabilityDefinition(UUID.randomUUID(), key, key, null, CapabilityDirection.READ,
                CapabilityValueType.NUMBER, "RPM", List.of("RPM"), min, max,
                List.of(), List.of(), null, Map.of(), Map.of());
    }
    private CapabilityDefinition definition(String key, CapabilityDirection direction,
            CapabilityValueType type, List<String> enumValues, List<String> commands,
            Map<String, Object> constraints) {
        return new CapabilityDefinition(UUID.randomUUID(), key, key, null, direction, type,
                null, List.of(), null, null, enumValues, commands, null, constraints, Map.of());
    }
}
