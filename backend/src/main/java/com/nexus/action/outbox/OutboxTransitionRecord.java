package com.nexus.action.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * One delivery-state change, as the dispatcher of P4.2 will report it. P4.1 defines the shape only; the durable
 * command state history that stores it (contract R06) arrives with its own migration and slice.
 *
 * @param from    state before the change, or null for the creation of the obligation
 * @param to      state after the change
 * @param attempt attempt counter at the time of the change
 * @param reason  stable machine code, never free text or an identifier
 */
public record OutboxTransitionRecord(UUID commandId, OutboxState from, OutboxState to, int attempt,
                                     String reason, String correlationId, Instant occurredAt) {

    public OutboxTransitionRecord {
        if (commandId == null || to == null || occurredAt == null) throw new IllegalArgumentException("commandId, to and occurredAt are required");
        if (from == null ? to != OutboxState.QUEUED : !from.canTransitionTo(to))
            throw new IllegalArgumentException("illegal outbox transition " + from + " -> " + to);
        if (attempt < 0) throw new IllegalArgumentException("attempt must not be negative");
    }
}
