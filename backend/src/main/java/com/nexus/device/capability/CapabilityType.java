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
    LED
}
