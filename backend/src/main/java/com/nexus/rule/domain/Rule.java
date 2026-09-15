package com.nexus.rule.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 13: {@code cooldownSeconds} is a new, optional field - null on
 * every rule persisted before Phase 13 (and the default for any new rule
 * that doesn't set it), meaning "no cooldown, current behavior." When set,
 * RuleMatcher will not re-match this rule again until that many seconds
 * have passed since its last durably-persisted match.
 */
public record Rule(
    UUID ruleId,
    String name,
    boolean enabled,
    int priority,
    List<RuleCondition> conditions,
    List<RuleAction> actions,
    Map<String, String> metadata,
    Integer cooldownSeconds
) {
    public Rule(UUID ruleId, String name, boolean enabled, int priority,
                List<RuleCondition> conditions, List<RuleAction> actions, Map<String, String> metadata) {
        this(ruleId, name, enabled, priority, conditions, actions, metadata, null);
    }
}
