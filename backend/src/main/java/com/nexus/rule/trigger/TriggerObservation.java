package com.nexus.rule.trigger;

import com.nexus.event.domain.DomainEvent;
import com.nexus.rule.domain.TriggerType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The triggering observation: a persisted legacy DomainEvent or a committed typed source fact. It is kept
 * apart from the frozen reported-state snapshot, which may already contain later observations.
 */
public record TriggerObservation(TriggerType type, UUID sourceId, UUID factId, DomainEvent legacyEvent, UUID deviceId,
        Instant occurredAt, Instant recordedAt, Map<String,Object> sourceRef, Map<String,Object> attributes,
        String correlationId, UUID parentMatchId, int chainDepth, List<UUID> causationRuleIds) {
    public TriggerObservation {
        sourceRef=sourceRef==null?Map.of():Map.copyOf(sourceRef);
        attributes=attributes==null?Map.of():Map.copyOf(attributes);
        causationRuleIds=causationRuleIds==null?List.of():List.copyOf(causationRuleIds);
    }
    public static TriggerObservation legacy(DomainEvent event){
        return new TriggerObservation(TriggerType.LEGACY_DOMAIN_EVENT,event.eventId(),null,event,event.deviceId(),
            event.timestamp(),event.timestamp(),Map.of(),Map.of(),event.correlationId(),null,0,List.of());
    }
    public boolean typed(){return type!=TriggerType.LEGACY_DOMAIN_EVENT;}
}
