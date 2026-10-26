package com.nexus.intelligence.evidence;

/**
 * Whether the platform holds a fact. The three states are never merged: PRESENT (a persisted value), ABSENT (the platform recorded that the value is
 * empty or that the thing did not happen, for example a null terminal reason on a queued command) and NOT_RECORDED (the platform has no record at all,
 * so nothing, positive or negative, may be claimed). An explanation may state a negative only from ABSENT evidence.
 */
public enum Presence {
    PRESENT, ABSENT, NOT_RECORDED
}
