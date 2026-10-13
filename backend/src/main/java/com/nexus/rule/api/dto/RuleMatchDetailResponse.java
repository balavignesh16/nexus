package com.nexus.rule.api.dto;

import com.nexus.rule.persistence.RuleMatchEntity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Full durable rule-match record (Tier 3 Phase 11) - the historical rule
 * snapshot and evaluation evidence exactly as persisted by
 * RuleMatchPersister at match time, never reconstructed from the current
 * RuleRegistry state. Deliberately exposes only eventId/correlationId for
 * the triggering event, not the DomainEvent object itself (which has a
 * known, separate, unfixed Jackson serialization gap) - the actual
 * observed value is already captured in evaluationEvidence.
 */
public record RuleMatchDetailResponse(
    UUID matchId,
    UUID ruleId,
    Map<String, Object> ruleSnapshot,
    UUID eventId,
    String correlationId,
    Map<String, Object> evaluationEvidence,
    Instant matchedAt,
    String message,
    UUID ruleVersionId,
    Long ruleRevision,
    Long executionEpoch
) {
    public RuleMatchDetailResponse(UUID matchId,UUID ruleId,Map<String,Object> snapshot,UUID eventId,String correlationId,Map<String,Object> evidence,Instant at,String message){this(matchId,ruleId,snapshot,eventId,correlationId,evidence,at,message,null,null,null);}
    public static RuleMatchDetailResponse from(RuleMatchEntity entity) {
        return new RuleMatchDetailResponse(
                entity.getMatchId(),
                entity.getRuleId(),
                entity.getRuleSnapshot(),
                entity.getEventId(),
                entity.getCorrelationId(),
                entity.getEvaluationEvidence(),
                entity.getMatchedAt(),
                entity.getMessage(),
                entity.getRuleVersionId(),entity.getRuleRevision(),entity.getExecutionEpoch()
        );
    }
}
