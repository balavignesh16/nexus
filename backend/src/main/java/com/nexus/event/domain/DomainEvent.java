package com.nexus.event.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DomainEvent(
    UUID eventId,
    EventType eventType,
    UUID deviceId,
    Instant timestamp,
    String source,
    Map<String, Object> payload,
    String severity,
    String correlationId
) {}
