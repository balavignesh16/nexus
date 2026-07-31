package com.nexus.rule.domain;

import com.nexus.event.domain.DomainEvent;

public record RuleEvaluationResult(
    boolean matched,
    Rule rule,
    DomainEvent event,
    String message
) {}
