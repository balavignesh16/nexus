package com.nexus.rule.domain;

import com.nexus.event.domain.DomainEvent;
import com.nexus.rule.snapshot.RuleEvaluationContext;

import java.util.List;

public record RuleEvaluationResult(
    boolean matched,
    Rule rule,
    DomainEvent event,
    String message,
    List<ConditionEvidence> evidence,
    @com.fasterxml.jackson.annotation.JsonIgnore RuleEvaluationContext context
) {
    public RuleEvaluationResult(boolean matched, Rule rule, DomainEvent event, String message, List<ConditionEvidence> evidence) {
        this(matched, rule, event, message, evidence, null);
    }
    public RuleEvaluationResult(boolean matched, Rule rule, DomainEvent event, String message) {
        this(matched, rule, event, message, List.of(), null);
    }
}
