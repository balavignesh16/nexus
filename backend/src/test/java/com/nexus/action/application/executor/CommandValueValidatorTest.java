package com.nexus.action.application.executor;

import com.nexus.device.capability.CapabilityType;
import com.nexus.device.capability.DeviceCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 13: unit coverage for the per-capability command value contract,
 * mirroring the Phase 12 simulator's actuators.py exactly. This is the
 * single source of truth reused by both CommandExecutor (execution-time)
 * and RuleRegistry (registration-time) - covered here once, not duplicated.
 */
class CommandValueValidatorTest {

    private DeviceCapability capabilityWithChannels(List<Integer> channels) {
        DeviceCapability capability = mock(DeviceCapability.class);
        when(capability.getMetadata()).thenReturn(Map.of("channels", channels));
        return capability;
    }

    @Test
    void led_fan_buzzer_acceptBoolean() {
        for (CapabilityType type : List.of(CapabilityType.LED, CapabilityType.FAN, CapabilityType.BUZZER)) {
            assertDoesNotThrow(() -> CommandValueValidator.validate(type, null, "SET_STATE", true));
        }
    }

    @Test
    void led_fan_buzzer_rejectNonBoolean() {
        for (CapabilityType type : List.of(CapabilityType.LED, CapabilityType.FAN, CapabilityType.BUZZER)) {
            assertThrows(CommandValueValidator.CommandValidationException.class,
                    () -> CommandValueValidator.validate(type, null, "SET_STATE", "on"));
        }
    }

    @Test
    void servo_acceptsInRangeInteger() {
        assertDoesNotThrow(() -> CommandValueValidator.validate(CapabilityType.SERVO, null, "SET_STATE", 90));
        assertDoesNotThrow(() -> CommandValueValidator.validate(CapabilityType.SERVO, null, "SET_STATE", 0));
        assertDoesNotThrow(() -> CommandValueValidator.validate(CapabilityType.SERVO, null, "SET_STATE", 180));
    }

    @Test
    void servo_rejectsOutOfRange() {
        assertThrows(CommandValueValidator.CommandValidationException.class,
                () -> CommandValueValidator.validate(CapabilityType.SERVO, null, "SET_STATE", -1));
        assertThrows(CommandValueValidator.CommandValidationException.class,
                () -> CommandValueValidator.validate(CapabilityType.SERVO, null, "SET_STATE", 181));
    }

    @Test
    void servo_rejectsNonIntegerOrFractional() {
        assertThrows(CommandValueValidator.CommandValidationException.class,
                () -> CommandValueValidator.validate(CapabilityType.SERVO, null, "SET_STATE", "90"));
        assertThrows(CommandValueValidator.CommandValidationException.class,
                () -> CommandValueValidator.validate(CapabilityType.SERVO, null, "SET_STATE", 90.5));
    }

    @Test
    void relay_acceptsValidChannelAndState() {
        DeviceCapability capability = capabilityWithChannels(List.of(1, 2, 3, 4));
        assertDoesNotThrow(() -> CommandValueValidator.validate(
                CapabilityType.RELAY, capability, "SET_STATE", Map.of("channel", 2, "state", true)));
    }

    @Test
    void relay_rejectsChannelNotInMetadata() {
        DeviceCapability capability = capabilityWithChannels(List.of(1, 2, 3, 4));
        assertThrows(CommandValueValidator.CommandValidationException.class, () -> CommandValueValidator.validate(
                CapabilityType.RELAY, capability, "SET_STATE", Map.of("channel", 9, "state", true)));
    }

    @Test
    void relay_rejectsMalformedValue() {
        DeviceCapability capability = capabilityWithChannels(List.of(1, 2, 3, 4));
        assertThrows(CommandValueValidator.CommandValidationException.class, () -> CommandValueValidator.validate(
                CapabilityType.RELAY, capability, "SET_STATE", true));
        assertThrows(CommandValueValidator.CommandValidationException.class, () -> CommandValueValidator.validate(
                CapabilityType.RELAY, capability, "SET_STATE", Map.of("channel", 1)));
        assertThrows(CommandValueValidator.CommandValidationException.class, () -> CommandValueValidator.validate(
                CapabilityType.RELAY, capability, "SET_STATE", Map.of("channel", "1", "state", true)));
    }

    @Test
    void unsupportedCommandVerb_rejectedForEveryCapability() {
        assertThrows(CommandValueValidator.CommandValidationException.class,
                () -> CommandValueValidator.validate(CapabilityType.LED, null, "TOGGLE", true));
    }

    @Test
    void readOnlyCapability_rejectedAsCommandTarget() {
        assertThrows(CommandValueValidator.CommandValidationException.class,
                () -> CommandValueValidator.validate(CapabilityType.TEMPERATURE, null, "SET_STATE", 25.0));
    }
}
