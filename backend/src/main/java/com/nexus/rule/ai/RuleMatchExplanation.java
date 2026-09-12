package com.nexus.rule.ai;

import java.util.List;
import java.util.UUID;

/**
 * The stable output contract for any rule-match explanation generator
 * (Tier 4 Phase 1). generatedBy is "DETERMINISTIC_FALLBACK" for
 * DeterministicExplanationRenderer's output in this phase; a future AI
 * reasoning boundary would produce "AI", letting a consumer structurally
 * distinguish the two without parsing summary text.
 */
public record RuleMatchExplanation(
    UUID matchId,
    String summary,
    List<Claim> claims,
    String actionNote,
    String generatedBy
) {
    public RuleMatchExplanation {
        claims = claims == null ? List.of() : List.copyOf(claims);
    }
}
