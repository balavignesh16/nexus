package com.nexus.action.api.dto;

import com.nexus.action.domain.AcknowledgementStatus;
import com.nexus.action.domain.ActionExecutionResult;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.action.domain.CommandStatus;
import java.time.Clock;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ActionHistoryResponse(
    UUID executionId,
    UUID requestId,
    UUID matchId,
    UUID deviceId,
    ActionType actionType,
    Map<String, Object> parameters,
    ExecutionStatus status,
    Instant timestamp,
    String message,
    AcknowledgementStatus ackStatus,
    Instant ackReceivedAt,
    String ackMessage,
    ExecutionStatus executionStatus,
    CommandStatus commandStatus,
    Instant publishedAt,
    Instant ackBackendReceivedAt,
    Instant ackDeviceTimestamp,
    Instant ackDeadlineAt
) {
    public ActionHistoryResponse(UUID executionId, UUID requestId, UUID matchId, UUID deviceId,
            ActionType actionType, Map<String,Object> parameters, ExecutionStatus status, Instant timestamp,
            String message, AcknowledgementStatus ackStatus, Instant ackReceivedAt, String ackMessage) {
        this(executionId,requestId,matchId,deviceId,actionType,parameters,status,timestamp,message,
                ackStatus,ackReceivedAt,ackMessage,status,null,null,null,null,null);
    }
    /**
     * Built from the immediate publish result only - ackStatus/ackReceivedAt/
     * ackMessage are always null here, since {@link ActionExecutionResult}
     * never carries ACK data (it is the return value of the synchronous
     * publish step, which happens before any ACK could possibly exist).
     * deviceId is also null: ActionExecutionResult/ActionExecutionRequest
     * never carry a deviceId field (unlike the persisted ActionHistoryRecord).
     */
    public static ActionHistoryResponse from(ActionExecutionResult result) {
        return new ActionHistoryResponse(
                result.executionId(),
                result.request().requestId(),
                result.request().matchId(),
                null,
                result.request().actionType(),
                result.request().parameters(),
                result.status(),
                result.timestamp(),
                result.message(),
                null,
                null,
                null,
                result.status(),
                result.request().actionType() != ActionType.SEND_COMMAND ? null :
                result.status() == ExecutionStatus.SUCCESS ? CommandStatus.PUBLISHED :
                        result.status() == ExecutionStatus.ACCEPTED_FOR_DISPATCH ? CommandStatus.ACCEPTED_FOR_DISPATCH : CommandStatus.FAILED,
                null,null,null,null
        );
    }

    /**
     * Tier 5E: built from the durably-persisted row, which is the only place
     * ACK data lives. ackStatus/ackReceivedAt/ackMessage are null for any
     * row that has not (yet, or ever) received a correlated ACK - including
     * every non-SEND_COMMAND action type.
     */
    public static ActionHistoryResponse from(ActionHistoryRecord record) {
        return from(record,Clock.systemUTC());
    }

    public static ActionHistoryResponse from(ActionHistoryRecord record, Clock clock) {
        return new ActionHistoryResponse(
                record.getRequestId(),
                record.getRequestId(),
                record.getMatchId(),
                record.getDeviceId(),
                record.getActionType(),
                record.getParameters(),
                record.getStatus(),
                record.getTimestamp(),
                record.getMessage(),
                record.getAckStatus(),
                record.getAckReceivedAt(),
                record.getAckMessage(),
                record.getStatus(), record.getActionType()==ActionType.SEND_COMMAND?CommandStatus.from(record,clock):null,
                record.getPublishedAt(),
                record.getAckBackendReceivedAt(),record.getAckDeviceTimestamp(),record.getAckDeadlineAt()
        );
    }
}
