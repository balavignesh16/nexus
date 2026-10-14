package com.nexus.rule.plan;

import java.util.UUID;

public record ActionDecision(ActionCandidate candidate,Disposition disposition,String reason,UUID winnerCandidateId) {
    public enum Disposition { SELECTED, COALESCED, SUPPRESSED_PRIORITY, SUPPRESSED_CONFLICT, SUPPRESSED_EXCLUSION, SUPPRESSED_EXCLUSION_CONFLICT }
    /** Only SELECTED dispatches. Any other (or unknown, future, held) disposition never reaches guarded acceptance. */
    public boolean dispatches(){return disposition==Disposition.SELECTED;}
}
