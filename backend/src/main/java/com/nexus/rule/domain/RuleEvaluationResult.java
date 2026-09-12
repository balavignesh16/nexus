package com.nexus.rule.domain;

import com.nexus.event.domain.DomainEvent;

import java.util.List;

public record RuleEvaluationResult(
    boolean matched,
    Rule rule,
    DomainEvent event,
    String message,
    List<ConditionEvidence> evidence
) {
    public RuleEvaluationResult(boolean matched, Rule rule, DomainEvent event, String message) {
        this(matched, rule, event, message, List.of());
    }
}
