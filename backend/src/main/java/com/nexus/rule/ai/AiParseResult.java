package com.nexus.rule.ai;

import java.util.List;

/**
 * Result of AiCandidateParser.parse() (Tier 4 Phase 2A). Either the
 * top-level candidate parsed successfully (individual malformed claims may
 * still have been skipped - see skippedClaimReasons; the surviving claims
 * still go through GroundingChecker afterward), or the candidate as a
 * whole was rejected (malformed JSON, or a missing/invalid top-level
 * field with no safe partial interpretation).
 */
public record AiParseResult(
    boolean success,
    RuleMatchExplanation candidate,
    String failureReason,
    List<String> skippedClaimReasons
) {
    public AiParseResult {
        skippedClaimReasons = skippedClaimReasons == null ? List.of() : List.copyOf(skippedClaimReasons);
    }

    public static AiParseResult success(RuleMatchExplanation candidate, List<String> skippedClaimReasons) {
        return new AiParseResult(true, candidate, null, skippedClaimReasons);
    }

    public static AiParseResult malformed(String reason) {
        return new AiParseResult(false, null, reason, List.of());
    }
}
