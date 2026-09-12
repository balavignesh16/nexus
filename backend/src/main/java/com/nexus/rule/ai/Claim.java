package com.nexus.rule.ai;

import java.util.List;

/**
 * One claim within a RuleMatchExplanation (Tier 4 Phase 2A). Shape rules
 * (enforced by GroundingChecker, not by this record):
 * FACT requires a non-null evidenceRef, an assertedValue (possibly null -
 * see below), and empty supportingEvidenceRefs.
 * INFERENCE requires a null evidenceRef, a null assertedValue, and
 * non-empty supportingEvidenceRefs.
 * UNKNOWN requires evidenceRef, assertedValue, and supportingEvidenceRefs
 * to all be absent/empty.
 *
 * assertedValue is the structured value a FACT claim asserts its
 * evidenceRef resolves to - GroundingChecker verifies
 * resolve(evidenceRef) == assertedValue, not merely that evidenceRef
 * resolves to *something*. A Java record cannot distinguish "the
 * assertedValue JSON key was present with value null" from "the key was
 * absent entirely" - that distinction can only be made while the raw JSON
 * is still available, so AiCandidateParser (not this record) is
 * responsible for rejecting a FACT claim whose JSON omitted the
 * assertedValue key, while accepting one that explicitly set it to null.
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
    Object assertedValue,
    List<String> supportingEvidenceRefs
) {
    public Claim {
        if (supportingEvidenceRefs != null) {
            supportingEvidenceRefs = List.copyOf(supportingEvidenceRefs);
        }
    }
}
