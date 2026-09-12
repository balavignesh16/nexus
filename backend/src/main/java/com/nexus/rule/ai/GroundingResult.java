package com.nexus.rule.ai;

import java.util.List;

/**
 * Result of GroundingChecker.check() (Tier 4 Phase 1). validated always
 * carries the context's matchId and any claims that passed grounding
 * validation - an empty claims list signals the caller must fall back to
 * DeterministicExplanationRenderer entirely, since nothing from the
 * candidate explanation survived.
 */
public record GroundingResult(
    RuleMatchExplanation validated,
    int rejectedClaimCount,
    List<String> rejectionReasons
) {
    public GroundingResult {
        rejectionReasons = rejectionReasons == null ? List.of() : List.copyOf(rejectionReasons);
    }
}
