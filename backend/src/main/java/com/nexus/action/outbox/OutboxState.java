package com.nexus.action.outbox;

/**
 * Delivery state of one accepted command (Phase 4 contract R05). PUBLISHED and DEAD_LETTER are terminal for
 * delivery; every later fact about the command (ACK, timeout, verification) is recorded elsewhere and never
 * changes this state. The legal transitions here are mirrored by the database trigger of V37.
 */
public enum OutboxState {
    QUEUED, IN_FLIGHT, RETRY_WAIT, PUBLISHED, DEAD_LETTER;

    public boolean isTerminal() {
        return this == PUBLISHED || this == DEAD_LETTER;
    }

    /** True while a delivery obligation still exists (it must never be deleted by retention). */
    public boolean isUnresolved() {
        return !isTerminal();
    }

    /** True only for the transitions of contract R05; a same-state write is not a transition. */
    public boolean canTransitionTo(OutboxState next) {
        return switch (this) {
            case QUEUED, RETRY_WAIT -> next == IN_FLIGHT || next == DEAD_LETTER;
            case IN_FLIGHT -> next == PUBLISHED || next == RETRY_WAIT || next == DEAD_LETTER;
            case PUBLISHED, DEAD_LETTER -> false;
        };
    }
}
