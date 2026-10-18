package com.nexus.intelligence.draft;

/** Why no draft was stored. Closed set; nothing of the model text is carried. */
public enum DraftRefusal {
    GOAL_INVALID, NO_DEVICES, MODEL_REFUSED, MALFORMED_CANDIDATE, MISSING_EVIDENCE, UNRESOLVED_EVIDENCE, VALIDATOR_UNAVAILABLE
}
