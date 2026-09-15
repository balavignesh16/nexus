package com.nexus.device.command.api.dto;

import com.nexus.device.capability.CapabilityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Phase 13: {@code value} was {@code Boolean} (LED-only). Widened to
 * {@code Object} so RELAY ({"channel":int,"state":bool}), SERVO (integer
 * 0-180), FAN/BUZZER (boolean) can all be manually dispatched too - actual
 * type/shape validation happens in CommandValueValidator (shared with
 * CommandExecutor and rule-action registration validation), not here.
 * Existing callers sending a plain JSON boolean (e.g. the LED toggle
 * control) are unaffected: Jackson deserializes a JSON boolean into
 * Boolean regardless of whether the target field is typed Boolean or
 * Object.
 */
public record CreateDeviceCommandRequest(
    @NotNull CapabilityType capability,
    @NotBlank String command,
    @NotNull Object value
) {}
