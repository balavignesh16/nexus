package com.nexus.rule.domain;

import com.nexus.device.capability.CapabilityType;

/**
 * Phase 13: {@code capabilityType} is a new, optional field - absent (null)
 * on every rule persisted before Phase 13, and Jackson deserializes an
 * absent JSONB key to null for a record component, so every pre-existing
 * rule remains byte-for-byte behaviorally unchanged (see RuleEvaluator).
 * When present, it is authoritative: the condition's actual value is read
 * from current DigitalTwinRegistry state for (the rule's device,
 * capabilityType), not from the triggering event's own payload.
 */
public record RuleCondition(
    RuleField field,
    RuleOperator operator,
    String expectedValue,
    CapabilityType capabilityType
) {
    public RuleCondition(RuleField field, RuleOperator operator, String expectedValue) {
        this(field, operator, expectedValue, null);
    }
}
