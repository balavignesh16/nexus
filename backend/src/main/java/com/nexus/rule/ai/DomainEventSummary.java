package com.nexus.rule.ai;

import java.time.Instant;
import java.util.UUID;

/**
 * Minimal, deterministic summary of the DomainEvent linked to a rule match
 * (Tier 4 Phase 1). Deliberately excludes payload - the actual observed
 * value that mattered to the match is already captured in
 * RuleMatchExplanationContext.evaluationEvidence.
 */
public record DomainEventSummary(
    UUID eventId,
    UUID deviceId,
    String eventType,
    Instant timestamp,
    String source,
    String severity
) {}
