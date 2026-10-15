package com.nexus.action.outbox;

/** Why a command reached DEAD_LETTER (contract R12). PUBLISHED rows carry no reason. */
public enum OutboxTerminalReason {
    RETRY_EXHAUSTED,
    EXPIRED,
    PERMANENT_FAILURE,
    PAYLOAD_INTEGRITY,
    /** The last attempt may have reached the broker but could not be confirmed or retried; never reported as a plain failure. */
    UNCERTAIN_EXPIRED
}
