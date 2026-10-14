package com.nexus.rule.ai;

import java.time.Instant;
import java.util.UUID;

/**
 * One PERSISTED action candidate of a match (P3.5/P3.6): what arbitration decided for it and what the executor evidence proved afterwards.
 * Every field is read from durable rows; nothing is recomputed from today's rule or Twin state. Publication ({@code historyStatus}/{@code publishedAt}),
 * device acknowledgement ({@code ackStatus}), the persisted P2.4 verification ({@code verificationStatus}, qualified {@code readbackEvidence}) and the
 * guarded-acceptance {@code outcome} are SEPARATE facts and none implies another.
 */
public record CandidateSummary(
    UUID candidateId,
    UUID ruleId,
    int ordinal,
    String actionType,
    String targetKey,
    int priority,
    String disposition,
    String reason,
    String winnerActionType,
    String winnerTargetKey,
    String outcome,
    String outcomeReason,
    String historyStatus,
    Instant publishedAt,
    String ackStatus,
    String verificationStatus,
    String readbackEvidence,
    String verificationReason
) {
    /** A candidate whose persisted record carries no verification fact. */
    public CandidateSummary(UUID candidateId, UUID ruleId, int ordinal, String actionType, String targetKey, int priority, String disposition, String reason,
            String winnerActionType, String winnerTargetKey, String outcome, String outcomeReason, String historyStatus, Instant publishedAt, String ackStatus) {
        this(candidateId, ruleId, ordinal, actionType, targetKey, priority, disposition, reason, winnerActionType, winnerTargetKey, outcome, outcomeReason,
            historyStatus, publishedAt, ackStatus, null, null, null);
    }
}
