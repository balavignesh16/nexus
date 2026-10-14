package com.nexus.rule.snapshot;

import com.nexus.rule.trigger.TriggerObservation;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-rule evaluation context. The trigger observation is kept apart from the captured
 * reported state: later observations may already exist at snapshot acquisition.
 */
public record RuleEvaluationContext(UUID evaluationId,RuleEvaluationSnapshot snapshot,CapturedRule captured,TriggerObservation trigger) {
    public Instant evaluationTime(){return snapshot.evaluationTime();}
}
