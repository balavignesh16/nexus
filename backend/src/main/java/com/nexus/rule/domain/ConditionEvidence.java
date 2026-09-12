package com.nexus.rule.domain;

/**
 * Immutable evidence of one condition's evaluation outcome (Tier 3 Phase
 * 9B) - the actual value compared, what it was compared against, and
 * whether it matched. Collected in evaluation order by RuleEvaluator and
 * attached to RuleEvaluationResult so a durable rule-match record can later
 * answer "why did this rule match this event" without re-running the
 * evaluator.
 */
public record ConditionEvidence(
    RuleField field,
    RuleOperator operator,
    String expectedValue,
    Object actualValue,
    boolean conditionMet
) {}
