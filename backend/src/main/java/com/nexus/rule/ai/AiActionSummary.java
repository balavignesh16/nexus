package com.nexus.rule.ai;

/**
 * The only action information ever sent to an AI reasoning boundary
 * (Tier 4 Phase 2A). Deliberately named executorRecordedStatus, not
 * "status": this is the software action executor's own recorded return
 * value, not any independent confirmation that a physical effect
 * occurred. No requestId, no timestamp, no parameters - those carry no
 * reasoning value to a model and are not needed for explanation.
 */
public record AiActionSummary(
    String actionType,
    String executorRecordedStatus
) {}
