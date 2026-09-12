package com.nexus.rule.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The complete, immutable, deterministic input to any rule-match
 * explanation generator - deterministic template today
 * (DeterministicExplanationRenderer), AI reasoning in a future phase.
 * Nothing outside this object may be consulted by a generator or by
 * GroundingChecker (Tier 4 Phase 1).
 *
 * ruleSnapshot/evaluationEvidence are the historical values persisted by
 * RuleMatchPersister at match time - never re-derived from RuleRegistry or
 * re-evaluated. deviceId/domainEvent are the only fields allowed to be
 * null (the linked domain event may have expired under retention);
 * correlationId may also be null (nullable in rule_matches); actions is
 * never null, only possibly empty.
 */
public record RuleMatchExplanationContext(
    UUID matchId,
    UUID ruleId,
    Map<String, Object> ruleSnapshot,
    UUID eventId,
    String correlationId,
    Map<String, Object> evaluationEvidence,
    Instant matchedAt,
    String message,
    UUID deviceId,
    DomainEventSummary domainEvent,
    List<ActionSummary> actions
) {
    public RuleMatchExplanationContext {
        ruleSnapshot = ruleSnapshot == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(ruleSnapshot));
        evaluationEvidence = evaluationEvidence == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(evaluationEvidence));
        actions = actions == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(actions));
    }
}
