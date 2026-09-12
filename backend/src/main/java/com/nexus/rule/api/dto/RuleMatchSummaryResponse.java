package com.nexus.rule.api.dto;

import com.nexus.rule.persistence.RuleMatchEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight list-view representation of a durable rule match (Tier 3
 * Phase 11). Deliberately omits ruleSnapshot/evaluationEvidence - those
 * belong to the detail endpoint - so a history listing stays cheap even
 * with many rows. ruleName is read from the persisted snapshot, never from
 * the live RuleRegistry, so a historical listing remains historically
 * correct even after the live rule has since been edited.
 */
public record RuleMatchSummaryResponse(
    UUID matchId,
    UUID ruleId,
    String ruleName,
    Instant matchedAt,
    String correlationId,
    String message
) {
    public static RuleMatchSummaryResponse from(RuleMatchEntity entity) {
        Object name = entity.getRuleSnapshot() != null ? entity.getRuleSnapshot().get("name") : null;
        return new RuleMatchSummaryResponse(
                entity.getMatchId(),
                entity.getRuleId(),
                name != null ? name.toString() : null,
                entity.getMatchedAt(),
                entity.getCorrelationId(),
                entity.getMessage()
        );
    }
}
