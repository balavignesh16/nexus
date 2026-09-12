package com.nexus.rule.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sanitized, AI-facing subset of RuleMatchExplanationContext (Tier 4
 * Phase 2A) - the only data ever allowed to leave NEXUS for AI reasoning.
 *
 * Deliberately excludes matchId, ruleId, eventId, deviceId, correlationId,
 * action requestId, any physical-outcome/actuator-state representation
 * (none exists anywhere in NEXUS to exclude - see AiActionSummary),
 * current Digital Twin state, telemetry history, pipeline trace, and
 * current RuleRegistry state. Those identifiers/context remain
 * NEXUS-owned and are reattached/used deterministically outside this
 * boundary - the AI never needs to state an identifier verbatim, only
 * reason over the evidence.
 *
 * ruleSnapshot here has its own top-level "ruleId" key removed (it is
 * otherwise the full persisted historical Rule snapshot) - see
 * AiExplanationContextMapper, the one place responsible for that
 * decision.
 */
public record AiExplanationContext(
    Map<String, Object> ruleSnapshot,
    Map<String, Object> evaluationEvidence,
    Instant matchedAt,
    String message,
    List<AiActionSummary> actions
) {
    public AiExplanationContext {
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
