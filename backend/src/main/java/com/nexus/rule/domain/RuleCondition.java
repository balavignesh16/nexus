package com.nexus.rule.domain;

public record RuleCondition(
    RuleField field,
    RuleOperator operator,
    String expectedValue
) {}
