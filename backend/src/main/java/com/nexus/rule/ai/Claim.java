package com.nexus.rule.ai;

import java.util.List;

/**
 * One claim within a RuleMatchExplanation (Tier 4 Phase 1). Shape rules
 * (enforced by GroundingChecker, not by this record):
 * FACT requires a non-null evidenceRef and empty supportingEvidenceRefs.
 * INFERENCE requires a null evidenceRef and non-empty supportingEvidenceRefs.
 * UNKNOWN requires both evidenceRef and supportingEvidenceRefs to be absent/empty.
 *
 * A null supportingEvidenceRefs is deliberately NOT normalized to an empty
 * list here - it is a distinct, malformed shape (as a future AI candidate
 * might produce) that GroundingChecker must detect and reject explicitly,
 * not one silently made to look like a legitimately empty list.
 */
public record Claim(
    ClaimTier tier,
    String text,
    String evidenceRef,
    List<String> supportingEvidenceRefs
) {
    public Claim {
        if (supportingEvidenceRefs != null) {
            supportingEvidenceRefs = List.copyOf(supportingEvidenceRefs);
        }
    }
}
