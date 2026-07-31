package com.nexus.rule.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Rule(
    UUID ruleId,
    String name,
    boolean enabled,
    int priority,
    List<RuleCondition> conditions,
    List<RuleAction> actions,
    Map<String, String> metadata
) {}
