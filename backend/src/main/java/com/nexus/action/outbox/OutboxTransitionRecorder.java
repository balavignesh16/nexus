package com.nexus.action.outbox;

/**
 * Port through which a later slice persists delivery transitions in the same transaction as the change they
 * describe. P4.1 deliberately provides no implementation and no bean: nothing records or reads transitions yet.
 */
public interface OutboxTransitionRecorder {
    void record(OutboxTransitionRecord transition);
}
