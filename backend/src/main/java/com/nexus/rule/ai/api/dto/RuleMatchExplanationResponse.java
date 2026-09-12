package com.nexus.rule.ai.api.dto;

import com.nexus.rule.ai.Claim;
import com.nexus.rule.ai.RuleMatchExplanation;

import java.util.List;
import java.util.UUID;

/**
 * The public API shape for GET /api/v1/ai/explanations/rule-match/{matchId}
 * (Tier 4 Phase 2C) - a thin, deterministic mapping of RuleMatchExplanation.
 * Deliberately carries no operational/provider fields (no provider name,
 * latency, fallback reason, config state, or exception detail) - those
 * belong to metrics/logs, never the response body.
 */
public record RuleMatchExplanationResponse(
    UUID matchId,
    String summary,
    List<Claim> claims,
    String actionNote,
    String generatedBy
) {
    public static RuleMatchExplanationResponse from(RuleMatchExplanation explanation) {
        return new RuleMatchExplanationResponse(
                explanation.matchId(),
                explanation.summary(),
                explanation.claims(),
                explanation.actionNote(),
                explanation.generatedBy()
        );
    }
}
