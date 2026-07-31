package com.nexus.action.api.dto;

import com.nexus.action.domain.ActionExecutionResult;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ActionHistoryResponse(
    UUID executionId,
    UUID requestId,
    UUID matchId,
    ActionType actionType,
    Map<String, Object> parameters,
    ExecutionStatus status,
    Instant timestamp,
    String message
) {
    public static ActionHistoryResponse from(ActionExecutionResult result) {
        return new ActionHistoryResponse(
                result.executionId(),
                result.request().requestId(),
                result.request().matchId(),
                result.request().actionType(),
                result.request().parameters(),
                result.status(),
                result.timestamp(),
                result.message()
        );
    }
}
