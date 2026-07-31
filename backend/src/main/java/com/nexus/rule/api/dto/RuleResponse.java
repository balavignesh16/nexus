package com.nexus.rule.api.dto;

import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RuleResponse(
    UUID ruleId,
    String name,
    boolean enabled,
    int priority,
    List<RuleCondition> conditions,
    List<RuleAction> actions,
    Map<String, String> metadata
) {
    public static RuleResponse from(Rule rule) {
        return new RuleResponse(
                rule.ruleId(),
                rule.name(),
                rule.enabled(),
                rule.priority(),
                rule.conditions(),
                rule.actions(),
                rule.metadata()
        );
    }
}
