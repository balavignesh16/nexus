package com.nexus.device.capability.definition.validation;

import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.definition.CapabilityDefinition;
import com.nexus.device.capability.definition.CapabilityValueType;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityValueValidatorTest {
    private final CapabilityValueValidator validator = new CapabilityValueValidator();

    @Test void numberChecksTypeRangeAndUnit() {
        var definition = definition("motor_speed", CapabilityDirection.WRITE,
                CapabilityValueType.NUMBER, "RPM", List.of("RPM"),
                BigDecimal.ZERO, new BigDecimal("5000"), List.of(), List.of("SET"), Map.of());
        assertTrue(validate(definition, 2500, "RPM", CapabilityOperation.COMMAND, "SET").isValid());
        assertCode(CapabilityValidationCode.CAPABILITY_VALUE_OUT_OF_RANGE,
                validate(definition, -1, "RPM", CapabilityOperation.COMMAND, "SET"));
        assertCode(CapabilityValidationCode.CAPABILITY_VALUE_OUT_OF_RANGE,
                validate(definition, 5001, "RPM", CapabilityOperation.COMMAND, "SET"));
        assertCode(CapabilityValidationCode.CAPABILITY_TYPE_MISMATCH,
                validate(definition, "fast", "RPM", CapabilityOperation.COMMAND, "SET"));
        assertCode(CapabilityValidationCode.CAPABILITY_UNIT_INVALID,
                validate(definition, 1, "PERCENT", CapabilityOperation.COMMAND, "SET"));
    }

    @Test void validatesBooleanStringAndEnum() {
        var bool = definition("motion", CapabilityDirection.READ, CapabilityValueType.BOOLEAN,
                null, List.of(), null, null, List.of(), List.of(), Map.of());
        assertTrue(validate(bool, true, null, CapabilityOperation.TELEMETRY, null).isValid());
        assertTrue(validate(bool, false, null, CapabilityOperation.TELEMETRY, null).isValid());
        assertCode(CapabilityValidationCode.CAPABILITY_TYPE_MISMATCH,
                validate(bool, 1, null, CapabilityOperation.TELEMETRY, null));
        var string = definition("version", CapabilityDirection.READ, CapabilityValueType.STRING,
                null, List.of(), null, null, List.of(), List.of(), Map.of());
        assertTrue(validate(string, "1.0", null, CapabilityOperation.TELEMETRY, null).isValid());
        var mode = definition("mode", CapabilityDirection.READ, CapabilityValueType.ENUM,
                null, List.of(), null, null, List.of("AUTO", "MANUAL"), List.of(), Map.of());
        assertTrue(validate(mode, "AUTO", null, CapabilityOperation.TELEMETRY, null).isValid());
        assertCode(CapabilityValidationCode.CAPABILITY_ENUM_VALUE_INVALID,
                validate(mode, "BROKEN", null, CapabilityOperation.TELEMETRY, null));
    }

    @Test void validatesRelayShapeCommandAndDirection() {
        var relay = definition("relay", CapabilityDirection.WRITE, CapabilityValueType.OBJECT,
                "BOOLEAN", List.of("BOOLEAN"), null, null, List.of(), List.of("SET_STATE"),
                Map.of("required", List.of("channel", "state")));
        assertTrue(validate(relay, Map.of("channel", 1, "state", true), "BOOLEAN",
                CapabilityOperation.COMMAND, "SET_STATE").isValid());
        assertCode(CapabilityValidationCode.CAPABILITY_OBJECT_INVALID,
                validate(relay, Map.of("channel", 1), "BOOLEAN", CapabilityOperation.COMMAND, "SET_STATE"));
        assertCode(CapabilityValidationCode.CAPABILITY_OBJECT_INVALID,
                validate(relay, Map.of("channel", 0, "state", true), "BOOLEAN",
                        CapabilityOperation.COMMAND, "SET_STATE"));
        assertCode(CapabilityValidationCode.CAPABILITY_COMMAND_UNSUPPORTED,
                validate(relay, Map.of("channel", 1, "state", true), "BOOLEAN",
                        CapabilityOperation.COMMAND, "TOGGLE"));
        assertCode(CapabilityValidationCode.CAPABILITY_DIRECTION_INVALID,
                validate(relay, Map.of("channel", 1, "state", true), "BOOLEAN",
                        CapabilityOperation.TELEMETRY, null));
    }

    private CapabilityValidationResult validate(CapabilityDefinition d, Object value, String unit,
            CapabilityOperation operation, String command) {
        return validator.validate(d, value, unit, operation, command);
    }
    private void assertCode(CapabilityValidationCode code, CapabilityValidationResult result) {
        assertFalse(result.isValid());
        assertEquals(code, result.errors().get(0).code());
    }
    private CapabilityDefinition definition(String key, CapabilityDirection direction,
            CapabilityValueType type, String unit, List<String> units, BigDecimal min,
            BigDecimal max, List<String> enumValues, List<String> commands,
            Map<String, Object> constraints) {
        return new CapabilityDefinition(UUID.randomUUID(), key, key, null, direction, type,
                unit, units, min, max, enumValues, commands, null, constraints, Map.of());
    }
}
