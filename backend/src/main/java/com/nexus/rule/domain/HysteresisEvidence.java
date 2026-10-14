package com.nexus.rule.domain;

/** How a hysteresis condition decided: the latch before and after, and why. UNKNOWN_UNCHANGED means the evidence was not eligible and the latch was left alone. */
public record HysteresisEvidence(String conditionId, String enter, String exit, boolean latchedBefore, boolean latchedAfter, String decision) {
    public boolean transition() { return latchedBefore != latchedAfter; }
}
