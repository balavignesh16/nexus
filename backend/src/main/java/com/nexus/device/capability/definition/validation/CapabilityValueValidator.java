package com.nexus.device.capability.definition.validation;

import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.definition.CapabilityDefinition;
import com.nexus.device.capability.definition.CapabilityValueType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CapabilityValueValidator {
    public CapabilityValidationResult validate(CapabilityDefinition definition, Object value,
            String unit, CapabilityOperation operation, String command) {
        if (operation == CapabilityOperation.TELEMETRY && definition.getDirection() != CapabilityDirection.READ) {
            return error(definition, CapabilityValidationCode.CAPABILITY_DIRECTION_INVALID,
                    "Capability does not accept telemetry", Map.of("direction", "READ"), value, unit);
        }
        if (operation == CapabilityOperation.COMMAND && definition.getDirection() != CapabilityDirection.WRITE) {
            return error(definition, CapabilityValidationCode.CAPABILITY_DIRECTION_INVALID,
                    "Capability does not accept commands", Map.of("direction", "WRITE"), value, unit);
        }
        if (operation == CapabilityOperation.COMMAND
                && (command == null || !definition.getSupportedCommands().contains(command))) {
            return error(definition, CapabilityValidationCode.CAPABILITY_COMMAND_UNSUPPORTED,
                    "Command is not supported", Map.of("commands", definition.getSupportedCommands()), value, unit);
        }
        if (unit != null && !definition.getAllowedUnits().isEmpty()
                && !definition.getAllowedUnits().contains(unit)) {
            return error(definition, CapabilityValidationCode.CAPABILITY_UNIT_INVALID,
                    "Unit is not allowed", Map.of("allowedUnits", definition.getAllowedUnits()), value, unit);
        }
        return switch (definition.getValueType()) {
            case NUMBER -> validateNumber(definition, value, unit);
            case BOOLEAN -> value instanceof Boolean ? CapabilityValidationResult.valid()
                    : typeError(definition, value, unit);
            case STRING -> value instanceof String ? CapabilityValidationResult.valid()
                    : typeError(definition, value, unit);
            case ENUM -> value instanceof String text && definition.getEnumValues().contains(text)
                    ? CapabilityValidationResult.valid()
                    : error(definition, CapabilityValidationCode.CAPABILITY_ENUM_VALUE_INVALID,
                            "Value is not an allowed enum member",
                            Map.of("enumValues", definition.getEnumValues()), value, unit);
            case OBJECT -> validateObject(definition, value, unit);
        };
    }

    private CapabilityValidationResult validateNumber(CapabilityDefinition definition, Object value, String unit) {
        if (!(value instanceof Number number)) return typeError(definition, value, unit);
        BigDecimal decimal;
        try { decimal = new BigDecimal(number.toString()); }
        catch (NumberFormatException exception) { return typeError(definition, value, unit); }
        if ((definition.getMinimum() != null && decimal.compareTo(definition.getMinimum()) < 0)
                || (definition.getMaximum() != null && decimal.compareTo(definition.getMaximum()) > 0)) {
            Map<String, Object> expected = new LinkedHashMap<>();
            if (definition.getMinimum() != null) expected.put("minimum", definition.getMinimum());
            if (definition.getMaximum() != null) expected.put("maximum", definition.getMaximum());
            return error(definition, CapabilityValidationCode.CAPABILITY_VALUE_OUT_OF_RANGE,
                    "Numeric value is outside the allowed range", expected, value, unit);
        }
        return CapabilityValidationResult.valid();
    }

    private CapabilityValidationResult validateObject(CapabilityDefinition definition, Object value, String unit) {
        if (!(value instanceof Map<?, ?> map)) return typeError(definition, value, unit);
        Object required = definition.getObjectConstraints().get("required");
        if (required instanceof Iterable<?> fields) {
            for (Object field : fields) {
                if (!map.containsKey(String.valueOf(field))) {
                    return error(definition, CapabilityValidationCode.CAPABILITY_OBJECT_INVALID,
                            "Required object field is missing", Map.of("required", required), value, unit);
                }
            }
        }
        if (map.containsKey("channel") && (!(map.get("channel") instanceof Number channel)
                || channel.intValue() < 1)) {
            return error(definition, CapabilityValidationCode.CAPABILITY_OBJECT_INVALID,
                    "Relay channel must be a positive integer", Map.of("channelMinimum", 1), value, unit);
        }
        if (map.containsKey("state") && !(map.get("state") instanceof Boolean)) {
            return error(definition, CapabilityValidationCode.CAPABILITY_OBJECT_INVALID,
                    "Relay state must be boolean", Map.of("stateType", "BOOLEAN"), value, unit);
        }
        return CapabilityValidationResult.valid();
    }

    private CapabilityValidationResult typeError(CapabilityDefinition definition, Object value, String unit) {
        return error(definition, CapabilityValidationCode.CAPABILITY_TYPE_MISMATCH,
                "Value does not match capability value type",
                Map.of("valueType", definition.getValueType().name()), value, unit);
    }

    private CapabilityValidationResult error(CapabilityDefinition definition, CapabilityValidationCode code,
            String message, Map<String, Object> expected, Object value, String unit) {
        Map<String, Object> received = new LinkedHashMap<>();
        received.put("value", value);
        if (unit != null) received.put("unit", unit);
        return CapabilityValidationResult.invalid(
                new CapabilityValidationError(code, message, definition.getKey(), expected, received));
    }
}
