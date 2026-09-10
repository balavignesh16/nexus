package com.nexus.event.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "domain_events")
public class DomainEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "source")
    private String source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "correlation_id")
    private String correlationId;

    protected DomainEvent() {}

    public DomainEvent(UUID eventId, EventType eventType, UUID deviceId, Instant timestamp, String source, Map<String, Object> payload, String severity, String correlationId) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.deviceId = deviceId;
        this.timestamp = timestamp;
        this.source = source;
        this.payload = payload;
        this.severity = severity;
        this.correlationId = correlationId;
    }

    public UUID eventId() { return eventId; }
    public EventType eventType() { return eventType; }
    public UUID deviceId() { return deviceId; }
    public Instant timestamp() { return timestamp; }
    public String source() { return source; }
    public Map<String, Object> payload() { return payload; }
    public String severity() { return severity; }
    public String correlationId() { return correlationId; }
}
