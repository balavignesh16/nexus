package com.nexus.rule.ai.api.dto;

import com.nexus.intelligence.model.ProviderIdentity;
import com.nexus.rule.ai.Claim;
import com.nexus.rule.ai.RuleMatchExplanation;

import java.util.List;
import java.util.UUID;

/**
 * The public API shape for GET /api/v1/ai/explanations/rule-match/{matchId}
 * (Tier 4 Phase 2C) - a thin, deterministic mapping of RuleMatchExplanation.
 * Deliberately carries no operational fields (no latency, fallback reason,
 * config state, or exception detail) - those belong to metrics/logs, never
 * the response body. P6.5 adds {@code provenance}: WHICH provider, model and
 * (hashed, non-secret) configuration produced the text when {@code generatedBy}
 * is AI, and the fixed "none" identity for the deterministic path, so a reader
 * and an evaluation can always tell what wrote the words (ledger P6-016).
 */
public record RuleMatchExplanationResponse(
    UUID matchId,
    String summary,
    List<Claim> claims,
    String actionNote,
    String generatedBy,
    ProviderIdentity provenance
) {
    public RuleMatchExplanationResponse(UUID matchId, String summary, List<Claim> claims, String actionNote, String generatedBy) {
        this(matchId, summary, claims, actionNote, generatedBy, ProviderIdentity.NONE);
    }

    public static RuleMatchExplanationResponse from(RuleMatchExplanation explanation) {
        return from(explanation, ProviderIdentity.NONE);
    }

    /** {@code producer} is the identity of the provider that wrote an AI explanation; it is ignored (the none identity is used) for any other generatedBy. */
    public static RuleMatchExplanationResponse from(RuleMatchExplanation explanation, ProviderIdentity producer) {
        return new RuleMatchExplanationResponse(
                explanation.matchId(),
                explanation.summary(),
                explanation.claims(),
                explanation.actionNote(),
                explanation.generatedBy(),
                "AI".equals(explanation.generatedBy()) && producer != null ? producer : ProviderIdentity.NONE
        );
    }
}
