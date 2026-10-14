package com.nexus.rule.plan;

import java.util.UUID;

public record ActionDecision(ActionCandidate candidate,Disposition disposition,String reason,UUID winnerCandidateId) {
    public enum Disposition { SELECTED, COALESCED, SUPPRESSED_PRIORITY, SUPPRESSED_CONFLICT }
    public boolean dispatches(){return disposition==Disposition.SELECTED;}
}
