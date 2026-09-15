package com.nexus.action.application.executor;

import com.nexus.device.capability.CapabilityType;
import com.nexus.device.capability.DeviceCapability;

import java.util.List;
import java.util.Map;

/**
 * Phase 13: per-capability command value validation, mirroring the Phase 12
 * simulator's own actuator contract exactly (simulator/src/nexus_simulator/actuators.py) -
 * the simulator is authoritative and unchanged; this class exists so the
 * backend rejects an invalid command BEFORE ever publishing to MQTT,
 * instead of relying on the device to reject it after a round trip.
 *
 * Reused as the single source of truth by both CommandExecutor (execution-time,
 * defense-in-depth - capabilities can change after a rule was created) and
 * RuleRegistry (registration-time, fail-fast).
 */
public final class CommandValueValidator {

    public static final String SET_STATE = "SET_STATE";

    private CommandValueValidator() {
    }

    public static final class CommandValidationException extends RuntimeException {
        public CommandValidationException(String message) {
            super(message);
        }
    }

    /**
     * Validates {@code command} and {@code value} against what
     * {@code capabilityType} is known to support, using {@code capability}'s
     * own metadata (e.g. RELAY's channel list) where relevant. Throws
     * {@link CommandValidationException} with a clear message on any
     * violation; returns normally (void) when valid - callers already have
     * the validated value in hand (it was the input), there is nothing to
     * transform or return.
     */
    public static void validate(CapabilityType capabilityType, DeviceCapability capability, String command, Object value) {
        if (!SET_STATE.equals(command)) {
            throw new CommandValidationException("Unsupported command: " + command);
        }

        switch (capabilityType) {
            case LED, FAN, BUZZER -> validateBoolean(capabilityType, value);
            case SERVO -> validateServo(value);
            case RELAY -> validateRelay(capability, value);
            default -> throw new CommandValidationException(capabilityType + " is not a WRITE capability that accepts commands");
        }
    }

    private static void validateBoolean(CapabilityType capabilityType, Object value) {
        if (!(value instanceof Boolean)) {
            throw new CommandValidationException("Value for " + capabilityType + " must be a boolean");
        }
    }

    private static void validateServo(Object value) {
        int angle = requireWholeNumber(value, "Value for SERVO must be an integer (0-180)");
        if (angle < 0 || angle > 180) {
            throw new CommandValidationException("Value for SERVO must be between 0 and 180, got " + angle);
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateRelay(DeviceCapability capability, Object value) {
        if (!(value instanceof Map)) {
            throw new CommandValidationException("Value for RELAY must be an object with 'channel' and 'state'");
        }
        Map<String, Object> valueMap = (Map<String, Object>) value;

        int channel = requireWholeNumber(valueMap.get("channel"), "RELAY value.channel must be an integer");
        if (!(valueMap.get("state") instanceof Boolean)) {
            throw new CommandValidationException("RELAY value.state must be a boolean");
        }

        List<Integer> validChannels = extractChannels(capability);
        if (!validChannels.contains(channel)) {
            throw new CommandValidationException("RELAY channel " + channel + " is not valid for this device (valid: " + validChannels + ")");
        }
    }

    /**
     * Jackson/Hibernate deserialize an untyped JSON integer into Integer,
     * Long, or (rarely) another Number subtype depending on the exact
     * binder in play - never assume a specific boxed type, but never accept
     * a fractional value (e.g. a servo angle of 90.5) either.
     */
    private static int requireWholeNumber(Object value, String errorMessage) {
        if (!(value instanceof Number number)) {
            throw new CommandValidationException(errorMessage);
        }
        double asDouble = number.doubleValue();
        if (asDouble != Math.floor(asDouble) || Double.isInfinite(asDouble)) {
            throw new CommandValidationException(errorMessage);
        }
        return number.intValue();
    }

    private static List<Integer> extractChannels(DeviceCapability capability) {
        Map<String, Object> metadata = capability != null ? capability.getMetadata() : null;
        if (metadata == null || !(metadata.get("channels") instanceof List<?> rawChannels)) {
            throw new CommandValidationException("RELAY capability has no metadata.channels configured");
        }
        return rawChannels.stream()
                .map(c -> ((Number) c).intValue())
                .toList();
    }
}
