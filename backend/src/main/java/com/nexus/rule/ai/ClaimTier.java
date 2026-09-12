package com.nexus.rule.ai;

/**
 * Tier 4 Phase 1: classifies a single claim in a RuleMatchExplanation.
 * FACT must be directly grounded in retrieved evidence (evidenceRef).
 * INFERENCE is a reasoned connection between two or more facts
 * (supportingEvidenceRefs), never disguised as a directly observed fact.
 * UNKNOWN explicitly names something that cannot be established from
 * available evidence.
 */
public enum ClaimTier {
    FACT,
    INFERENCE,
    UNKNOWN
}
