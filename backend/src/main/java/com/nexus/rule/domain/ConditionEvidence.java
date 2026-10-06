package com.nexus.rule.domain;

import java.time.Instant;

/**
 * Immutable evidence of one condition's evaluation outcome (Tier 3 Phase
 * 9B) - the actual value compared, what it was compared against, and
 * whether it matched. Collected in evaluation order by RuleEvaluator and
 * attached to RuleEvaluationResult so a durable rule-match record can later
 * answer "why did this rule match this event" without re-running the
 * evaluator.
 *
 * Phase 13: {@code stateTimestamp} is populated (from DigitalTwin.latestTimestamp)
 * only for capability-aware conditions evaluated against current
 * DigitalTwinRegistry state rather than the triggering event's own payload -
 * its presence itself documents where the value came from. Null for every
 * legacy, event-payload-based condition.
 */
public record ConditionEvidence(
    RuleField field,
    RuleOperator operator,
    String expectedValue,
    Object actualValue,
    boolean conditionMet,
    Instant stateTimestamp,
    String capabilityKey,
    java.util.UUID definitionId,
    java.util.UUID bindingId,
    String valueType,
    String unit,
    String stateQuality,
    String qualityReason,
    Instant qualityEvaluatedAt,
    Instant receivedAt,
    Integer freshnessThresholdSeconds,
    String connectivity
) {
    public ConditionEvidence(RuleField field, RuleOperator operator, String expectedValue,
                              Object actualValue, boolean conditionMet) {
        this(field, operator, expectedValue, actualValue, conditionMet, null,null,null,null,null,null,null,null,null,null,null,null);
    }
    public ConditionEvidence(RuleField field,RuleOperator operator,String expectedValue,Object actualValue,boolean met,Instant timestamp){this(field,operator,expectedValue,actualValue,met,timestamp,null,null,null,null,null,null,null,null,null,null,null);}
    public ConditionEvidence withQuality(String quality,String reason,Instant evaluatedAt,Instant receivedAt,Integer threshold,String connectivity){
        return new ConditionEvidence(field,operator,expectedValue,actualValue,conditionMet,stateTimestamp,capabilityKey,
                definitionId,bindingId,valueType,unit,quality,reason,evaluatedAt,receivedAt,threshold,connectivity);
    }
}
