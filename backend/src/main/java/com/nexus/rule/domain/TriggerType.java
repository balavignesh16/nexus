package com.nexus.rule.domain;

/** Closed set of Phase 3 trigger families (contract C12). */
public enum TriggerType {
    LEGACY_DOMAIN_EVENT, ACCEPTED_TELEMETRY, TWIN_STATE_CHANGED, CONNECTIVITY_CHANGED,
    COMMAND_PUBLICATION_FAILED, COMMAND_ACK_REJECTED, COMMAND_ACK_TIMED_OUT,
    VERIFICATION_CHANGED, VERIFICATION_TIMED_OUT, MANUAL, SCHEDULE, TEMPORAL_DUE, RULE_MATCHED;

    /** Only the legacy stream uses the legacy-event adapter (its actions still pass through the persisted plan like every other trigger). */
    public boolean legacy(){return this==LEGACY_DOMAIN_EVENT;}
}
