package com.nexus.rule.ai;

/**
 * The AI-facing subset of a persisted candidate. No identifiers and no target (a target embeds a device id): only what is needed to
 * describe what arbitration decided and what the executor evidence proved. {@code executorRecordedStatus} and {@code deviceAckStatus} are what
 * SOFTWARE recorded, not confirmation of a physical effect.
 */
public record AiCandidateSummary(
    int ordinal,
    String actionType,
    int priority,
    String disposition,
    String reason,
    String winnerActionType,
    String acceptanceOutcome,
    String acceptanceOutcomeReason,
    String executorRecordedStatus,
    boolean publishedToBroker,
    String deviceAckStatus,
    String verificationStatus,
    boolean qualifiedReadbackRecorded
) {}
