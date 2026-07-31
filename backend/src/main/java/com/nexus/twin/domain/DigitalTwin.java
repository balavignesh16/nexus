package com.nexus.twin.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DigitalTwin(
    UUID deviceId,
    Instant latestTimestamp,
    String latestSensorType,
    Double latestValue,
    String latestUnit,
    Instant lastSeen,
    Map<String, Object> metadata
) {}
