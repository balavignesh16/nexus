package com.nexus.rule.persistence;

import java.time.Instant;

/**
 * Raised by the durable match gate when a competing instance (or an earlier match) already consumed the cooldown window or the
 * EDGE_TRUE latch. Nothing was written: the REQUIRES_NEW match transaction rolls back, so a suppressed match leaves no row and
 * cannot advance any authority.
 */
public class RuleMatchSuppressedException extends RuntimeException {
    public enum Reason { COOLDOWN, EDGE_LATCHED }
    private final Reason reason;
    private final transient Instant lastMatchedAt;
    public RuleMatchSuppressedException(Reason reason, Instant lastMatchedAt) {
        super(reason.name(), null, false, false);
        this.reason = reason; this.lastMatchedAt = lastMatchedAt;
    }
    public Reason reason() { return reason; }
    public Instant lastMatchedAt() { return lastMatchedAt; }
}
