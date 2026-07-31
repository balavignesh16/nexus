package com.nexus.action.domain;

import java.time.Instant;
import java.util.UUID;

public record ActionExecutionResult(
    UUID executionId,
    ActionExecutionRequest request,
    ExecutionStatus status,
    Instant timestamp,
    String message
) {}
