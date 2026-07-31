package com.nexus.event.api.dto;

import com.nexus.event.domain.DomainEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EventResponse(
    UUID eventId,
    String eventType,
    UUID deviceId,
    Instant timestamp,
    String source,
    Map<String, Object> payload,
    String severity
) {
    public static EventResponse from(DomainEvent event) {
        return new EventResponse(
                event.eventId(),
                event.eventType() != null ? event.eventType().name() : null,
                event.deviceId(),
                event.timestamp(),
                event.source(),
                event.payload(),
                event.severity()
        );
    }
}
