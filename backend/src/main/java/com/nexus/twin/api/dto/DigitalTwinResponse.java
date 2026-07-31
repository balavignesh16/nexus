package com.nexus.twin.api.dto;

import com.nexus.twin.domain.DigitalTwin;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DigitalTwinResponse(
    UUID deviceId,
    Instant latestTimestamp,
    String latestSensorType,
    Double latestValue,
    String latestUnit,
    Instant lastSeen,
    String quality,
    Map<String, Object> metadata
) {
    public static DigitalTwinResponse from(DigitalTwin twin) {
        return new DigitalTwinResponse(
                twin.deviceId(),
                twin.latestTimestamp(),
                twin.latestSensorType(),
                twin.latestValue(),
                twin.latestUnit(),
                twin.lastSeen(),
                calculateQuality(twin.lastSeen()),
                twin.metadata()
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
