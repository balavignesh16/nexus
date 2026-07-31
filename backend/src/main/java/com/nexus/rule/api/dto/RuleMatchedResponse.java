package com.nexus.rule.api.dto;

import com.nexus.rule.domain.RuleMatchedEvent;

import java.time.Instant;
import java.util.UUID;

public record RuleMatchedResponse(
    UUID matchId,
    Instant timestamp,
    Object result // RuleEvaluationResult representation
) {
    public static RuleMatchedResponse from(RuleMatchedEvent event) {
        return new RuleMatchedResponse(
                event.matchId(),
                event.timestamp(),
                event.result()
        );
    }
}
