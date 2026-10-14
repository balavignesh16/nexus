package com.nexus.rule.plan;

import java.util.UUID;

/**
 * The trigger batch one plan decides: a committed typed fact, or the persisted legacy DomainEvent. A plan has exactly one source and a
 * source has at most one plan, so redelivery reuses the earlier decision instead of deciding again.
 */
public record PlanSource(Kind kind, UUID id) {
    public enum Kind { FACT, EVENT }
    public static PlanSource fact(UUID factId) { return new PlanSource(Kind.FACT, factId); }
    public static PlanSource event(UUID eventId) { return new PlanSource(Kind.EVENT, eventId); }
}
