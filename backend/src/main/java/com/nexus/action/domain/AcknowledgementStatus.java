package com.nexus.action.domain;

/**
 * Tier 5E: whether the device acknowledged/processed a dispatched command -
 * deliberately separate from {@link ExecutionStatus}, which only ever means
 * "did NEXUS successfully publish the command to MQTT". ACCEPTED means the
 * device parsed the command, passed its own validation, and applied it
 * (e.g. called digitalWrite()) - it is NOT physical-outcome verification.
 */
public enum AcknowledgementStatus {
    ACCEPTED,
    REJECTED
}
