package com.nexus.device.capability;

/**
 * Closed vocabulary of what a device can measure or control (Tier 5C) -
 * NEXUS-authoritative, deliberately distinct from telemetry's own free-form
 * {@code sensorType} wire value (e.g. "TEMPERATURE_SENSOR"). A human
 * correspondence often exists (a device provisioned with a TEMPERATURE
 * capability typically publishes telemetry under sensorType
 * "TEMPERATURE_SENSOR"), but that correspondence is documentation only -
 * no code in this codebase translates between the two vocabularies, and a
 * capability row is never created, inferred, or modified from a telemetry
 * message.
 */
public enum CapabilityType {
    TEMPERATURE,
    HUMIDITY,
    MOTION,
    LED,

    // Phase 11: complete authoritative BOM vocabulary. Adding these enum
    // values establishes a stable capability contract for Phase 12
    // (simulation) and Phase 13 (rules/actions) - it does not itself
    // implement any command dispatch, validation, or simulated/firmware
    // behavior for them. WRITE-direction values here are metadata only,
    // exactly like LED already is (see CapabilityDirection).
    PRESSURE,
    GAS,
    BUTTON,
    RELAY,
    SERVO,
    FAN,
    BUZZER
}
