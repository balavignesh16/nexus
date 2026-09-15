package com.nexus.rule.domain;

import com.nexus.device.capability.CapabilityType;

/**
 * Phase 13: how a READ capability's value should be compared in a rule
 * condition. Deliberately scoped to condition evaluation only - WRITE
 * capability command values (esp. RELAY's structured {channel,state} shape)
 * do not fit this scalar model and are validated separately by
 * CommandValueValidator, not reused here.
 */
public enum CapabilityValueKind {
    NUMERIC,
    BOOLEAN;

    public static CapabilityValueKind of(CapabilityType type) {
        return switch (type) {
            case TEMPERATURE, HUMIDITY, PRESSURE, GAS -> NUMERIC;
            case MOTION, BUTTON -> BOOLEAN;
            default -> throw new IllegalArgumentException(
                    type + " is not a READ capability usable in a rule condition");
        };
    }
}
