package com.nexus.rule.domain;

import java.time.Instant;
import java.util.UUID;

public record RuleMatchedEvent(
    UUID matchId,
    Instant timestamp,
    RuleEvaluationResult result,
    String correlationId
) {}
