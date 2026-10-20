package com.nexus.intelligence.retrieval;

import java.util.UUID;

/**
 * One typed lookup. Only the fields a lookup type uses are read; every identifier is a UUID or an exact device name, every other value is validated by the service.
 * {@code limit} and {@code lookbackHours} are clamped to the configured maxima and the clamp is reported.
 */
public record LookupRequest(LookupType type, UUID deviceId, String deviceName, UUID ruleId, UUID commandId, String capabilityKey, Integer lookbackHours, Integer limit, String cursor) {
    public static LookupRequest of(LookupType type) {
        return new LookupRequest(type, null, null, null, null, null, null, null, null);
    }

    public LookupRequest withDevice(UUID id) {
        return new LookupRequest(type, id, deviceName, ruleId, commandId, capabilityKey, lookbackHours, limit, cursor);
    }

    public LookupRequest withDeviceName(String name) {
        return new LookupRequest(type, deviceId, name, ruleId, commandId, capabilityKey, lookbackHours, limit, cursor);
    }

    public LookupRequest withRule(UUID id) {
        return new LookupRequest(type, deviceId, deviceName, id, commandId, capabilityKey, lookbackHours, limit, cursor);
    }

    public LookupRequest withCommand(UUID id) {
        return new LookupRequest(type, deviceId, deviceName, ruleId, id, capabilityKey, lookbackHours, limit, cursor);
    }

    public LookupRequest withCapability(String key) {
        return new LookupRequest(type, deviceId, deviceName, ruleId, commandId, key, lookbackHours, limit, cursor);
    }

    public LookupRequest withLookbackHours(Integer hours) {
        return new LookupRequest(type, deviceId, deviceName, ruleId, commandId, capabilityKey, hours, limit, cursor);
    }

    public LookupRequest withLimit(Integer rows) {
        return new LookupRequest(type, deviceId, deviceName, ruleId, commandId, capabilityKey, lookbackHours, rows, cursor);
    }

    public LookupRequest withCursor(String next) {
        return new LookupRequest(type, deviceId, deviceName, ruleId, commandId, capabilityKey, lookbackHours, limit, next);
    }
}
