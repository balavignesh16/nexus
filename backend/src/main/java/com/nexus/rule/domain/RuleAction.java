package com.nexus.rule.domain;

import java.util.Map;

public record RuleAction(
    String actionType,
    Map<String, Object> parameters
) {}
