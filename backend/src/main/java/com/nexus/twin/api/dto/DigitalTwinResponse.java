package com.nexus.twin.api.dto;

import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.DesiredTwinState;
import com.nexus.action.domain.ActionHistoryRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public record DigitalTwinResponse(
    UUID deviceId,
    Instant latestTimestamp,
    String latestSensorType,
    Double latestValue,
    String latestUnit,
    Instant lastSeen,
    String quality,
    Map<String, Object> metadata,
    Map<String, CapabilityState> reportedState,
    Map<String, DesiredState> desiredState,
    Map<String, EffectiveState> effectiveState
) {
    public record DesiredState(String stateId, Object typedValue, String valueType, String unit,
            String source, UUID commandId, Instant requestedAt, Instant acceptedForDispatchAt,
            String deliveryStatus, String ackStatus) {}
    public record EffectiveState(String stateId, Object value, String source,
            Object observedValue, Object intentValue) {}
    /**
     * Latest state for one telemetry capability belonging to this device.
     * The map key in {@code reportedState} is the capability/sensor type.
     */
    public record CapabilityState(
        String stateId,
        Double value,
        Object typedValue,
        String valueType,
        String capabilityKey,
        UUID definitionId,
        UUID bindingId,
        String resolutionSource,
        String unit,
        Instant timestamp,
        Instant lastSeen,
        String quality,
        Map<String, Object> metadata
    ) {
        private static CapabilityState from(DigitalTwin twin) {
            return new CapabilityState(
                    twin.bindingId() != null ? "binding:" + twin.bindingId()
                            : "legacy:" + canonicalLegacy(twin.latestSensorType()),
                    twin.latestValue(),
                    twin.typedValue(),
                    twin.valueType() == null ? null : twin.valueType().name(),
                    twin.capabilityKey(),
                    twin.capabilityDefinitionId(),
                    twin.bindingId(),
                    twin.resolutionSource() == null ? null : twin.resolutionSource().name(),
                    twin.latestUnit(),
                    twin.latestTimestamp(),
                    twin.lastSeen(),
                    calculateQuality(twin.lastSeen()),
                    twin.metadata()
            );
        }
    }

    public static DigitalTwinResponse from(DigitalTwin twin) {
        return from(List.of(twin));
    }

    /**
     * Builds one device-level twin without throwing away its other capability
     * states. The legacy latest* fields continue to describe the most recently
     * updated capability so existing API clients remain compatible.
     */
    public static DigitalTwinResponse from(List<DigitalTwin> twins) {
        return from(twins, List.of(), Map.of());
    }

    public static DigitalTwinResponse from(List<DigitalTwin> twins, List<DesiredTwinState> desired,
            Map<UUID, ActionHistoryRecord> actions) {
        if ((twins == null || twins.isEmpty()) && (desired == null || desired.isEmpty())) {
            throw new IllegalArgumentException("At least one capability state is required");
        }

        List<DigitalTwin> reported = twins == null ? List.of() : twins;
        List<DesiredTwinState> intended = desired == null ? List.of() : desired;
        DigitalTwin latest = reported.stream()
                .max(Comparator.comparing(DigitalTwin::latestTimestamp))
                .orElse(null);

        Map<String, CapabilityState> reportedState = reported.stream()
                .sorted(Comparator.comparing(DigitalTwin::latestSensorType))
                .collect(Collectors.toMap(
                        DigitalTwin::latestSensorType,
                        CapabilityState::from,
                        (left, right) -> right,
                        LinkedHashMap::new
                ));

        Map<String, DesiredState> desiredState = new LinkedHashMap<>();
        Map<String, EffectiveState> effectiveState = new LinkedHashMap<>();
        for (DigitalTwin twin : reported) {
            String id = twin.bindingId() != null ? "binding:" + twin.bindingId()
                    : "legacy:" + canonicalLegacy(twin.latestSensorType());
            effectiveState.putIfAbsent(id, new EffectiveState(id, twin.typedValue(), "REPORTED",
                    twin.typedValue(), null));
        }
        for (DesiredTwinState state : intended) {
            String id = state.getTargetKey() + (state.getSubtargetKey().isEmpty() ? "" : "/" + state.getSubtargetKey());
            ActionHistoryRecord action = actions.get(state.getCommandId());
            desiredState.put(id, new DesiredState(id, state.typedValue(), state.getValueType().name(),
                    state.getUnit(), state.getSource(), state.getCommandId(), state.getRequestedAt(),
                    state.getAcceptedForDispatchAt(), action == null ? "UNKNOWN" : action.getStatus().name(),
                    action == null || action.getAckStatus() == null ? null : action.getAckStatus().name()));
            Object observed = null;
            for (DigitalTwin twin : reported) {
                if (state.getBindingId() != null && state.getBindingId().equals(twin.bindingId())
                        || state.getLegacyCapabilityType() != null
                        && state.getLegacyCapabilityType().equals(canonicalLegacy(twin.latestSensorType()))) {
                    observed = twin.typedValue();
                    break;
                }
            }
            effectiveState.put(id, new EffectiveState(id, observed != null ? observed : state.typedValue(),
                    observed != null ? "REPORTED" : "DESIRED_UNVERIFIED", observed, state.typedValue()));
        }

        return new DigitalTwinResponse(
                latest != null ? latest.deviceId() : intended.getFirst().getDeviceId(),
                latest == null ? null : latest.latestTimestamp(),
                latest == null ? null : latest.latestSensorType(),
                latest == null ? null : latest.latestValue(),
                latest == null ? null : latest.latestUnit(),
                latest == null ? null : latest.lastSeen(),
                calculateQuality(latest == null ? null : latest.lastSeen()),
                latest == null ? Map.of() : latest.metadata(),
                reportedState, desiredState, effectiveState
        );
    }

    private static String canonicalLegacy(String sensorType) {
        String key = sensorType.toUpperCase(java.util.Locale.ROOT);
        return key.endsWith("_SENSOR") ? key.substring(0, key.length() - 7) : key;
    }

    private static String calculateQuality(Instant lastSeen) {
        if (lastSeen == null) {
            return "UNKNOWN";
        }
        long secondsSinceLastSeen = Duration.between(lastSeen, Instant.now()).getSeconds();
        if (secondsSinceLastSeen < 30) {
            return "GOOD";
        } else if (secondsSinceLastSeen <= 120) {
            return "STALE";
        } else {
            return "OFFLINE";
        }
    }
}
