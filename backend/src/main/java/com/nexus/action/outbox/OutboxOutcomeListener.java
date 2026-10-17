package com.nexus.action.outbox;

/**
 * Narrow hook through which the rest of the application learns, AFTER the outcome transaction has committed, that a
 * queued command was published or ended in a dead letter. The dispatcher itself depends on no rule, twin or realtime
 * class; the implementation (action.application) forwards to them. A failure here is never a delivery failure: the
 * dispatcher logs it and the committed outcome stands.
 */
public interface OutboxOutcomeListener {
    void published(OutboxDeliveryOperations.Resolution resolution);

    void deadLettered(OutboxDeliveryOperations.Resolution resolution);

    /** A queued command was just recorded as held behind an earlier command whose delivery outcome was uncertain. */
    default void held(OutboxDeliveryOperations.Held held) {
    }
}
