package com.nexus.rule.domain;

import java.util.Map;

public record RuleAction(
    String actionType,
    Map<String, Object> parameters
) {
    @SuppressWarnings("unchecked")
    public RuleAction { if(parameters!=null)parameters=(Map<String,Object>)ImmutableRuleValue.freeze(parameters); }
}
