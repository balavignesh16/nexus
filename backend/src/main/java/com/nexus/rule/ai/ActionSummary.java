package com.nexus.rule.ai;

import java.time.Instant;
import java.util.UUID;

/**
 * Minimal, deterministic summary of one action_history row linked to a
 * rule match by matchId (Tier 4 Phase 1). No per-action "stub" flag: all
 * current executors are stubs, a platform-wide fact represented once via
 * the fixed disclaimer on RuleMatchExplanation.actionNote, not duplicated
 * per row here.
 */
public record ActionSummary(
    UUID requestId,
    String actionType,
    String status,
    Instant timestamp,
    String message
) {}
