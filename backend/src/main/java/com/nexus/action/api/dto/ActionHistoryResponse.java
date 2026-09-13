package com.nexus.action.api.dto;

import com.nexus.action.domain.AcknowledgementStatus;
import com.nexus.action.domain.ActionExecutionResult;
import com.nexus.action.domain.ActionHistoryRecord;
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
    String message,
    AcknowledgementStatus ackStatus,
    Instant ackReceivedAt,
    String ackMessage
) {
    /**
     * Built from the immediate publish result only - ackStatus/ackReceivedAt/
     * ackMessage are always null here, since {@link ActionExecutionResult}
     * never carries ACK data (it is the return value of the synchronous
     * publish step, which happens before any ACK could possibly exist).
     */
    public static ActionHistoryResponse from(ActionExecutionResult result) {
        return new ActionHistoryResponse(
                result.executionId(),
                result.request().requestId(),
                result.request().matchId(),
                result.request().actionType(),
                result.request().parameters(),
                result.status(),
                result.timestamp(),
                result.message(),
                null,
                null,
                null
        );
    }

    /**
     * Tier 5E: built from the durably-persisted row, which is the only place
     * ACK data lives. ackStatus/ackReceivedAt/ackMessage are null for any
     * row that has not (yet, or ever) received a correlated ACK - including
     * every non-SEND_COMMAND action type.
     */
    public static ActionHistoryResponse from(ActionHistoryRecord record) {
        return new ActionHistoryResponse(
                record.getRequestId(),
                record.getRequestId(),
                record.getMatchId(),
                record.getActionType(),
                record.getParameters(),
                record.getStatus(),
                record.getTimestamp(),
                record.getMessage(),
                record.getAckStatus(),
                record.getAckReceivedAt(),
                record.getAckMessage()
        );
    }
}
