package com.nexus.twin.api.dto;

import com.nexus.twin.domain.DigitalTwin;

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
    Map<String, CapabilityState> reportedState
) {
    /**
     * Latest state for one telemetry capability belonging to this device.
     * The map key in {@code reportedState} is the capability/sensor type.
     */
    public record CapabilityState(
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
        if (twins == null || twins.isEmpty()) {
            throw new IllegalArgumentException("At least one capability state is required");
        }

        DigitalTwin latest = twins.stream()
                .max(Comparator.comparing(DigitalTwin::latestTimestamp))
                .orElseThrow();

        Map<String, CapabilityState> reportedState = twins.stream()
                .sorted(Comparator.comparing(DigitalTwin::latestSensorType))
                .collect(Collectors.toMap(
                        DigitalTwin::latestSensorType,
                        CapabilityState::from,
                        (left, right) -> right,
                        LinkedHashMap::new
                ));

        return new DigitalTwinResponse(
                latest.deviceId(),
                latest.latestTimestamp(),
                latest.latestSensorType(),
                latest.latestValue(),
                latest.latestUnit(),
                latest.lastSeen(),
                calculateQuality(latest.lastSeen()),
                latest.metadata(),
                reportedState
        );
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
