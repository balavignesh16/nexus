package com.nexus.rule.ai;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single, deliberately small and reviewable place responsible for
 * deciding what data is allowed to leave NEXUS for AI reasoning (Tier 4
 * Phase 2A). A pure mapping from the full, internal
 * RuleMatchExplanationContext to the sanitized AiExplanationContext - no
 * other class should perform this data-minimization decision.
 */
@Component
public class AiExplanationContextMapper {

    public AiExplanationContext toAiContext(RuleMatchExplanationContext context) {
        return new AiExplanationContext(
                sanitizeRuleSnapshot(context.ruleSnapshot()),
                context.evaluationEvidence(),
                context.matchedAt(),
                context.message(),
                context.actions().stream()
                        .map(a -> new AiActionSummary(a.actionType(), a.status()))
                        .toList()
        );
    }

    /**
     * The persisted ruleSnapshot is a full conversion of the historical
     * Rule record, which includes its own "ruleId" key - an identifier
     * that must not leave NEXUS (per the data-minimization boundary), even
     * though the rest of the snapshot (name, conditions, actions,
     * metadata, enabled, priority) is required for explanation.
     */
    private Map<String, Object> sanitizeRuleSnapshot(Map<String, Object> ruleSnapshot) {
        Map<String, Object> sanitized = new LinkedHashMap<>(ruleSnapshot);
        sanitized.remove("ruleId");
        return sanitized;
    }
}
