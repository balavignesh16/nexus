package com.nexus.action.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "action_history")
public class ActionHistoryRecord {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    // Tier 5D: nullable - a manually-triggered command has no RuleMatch.
    @Column(name = "match_id")
    private UUID matchId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false)
    private ActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExecutionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", columnDefinition = "jsonb")
    private Map<String, Object> parameters;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "message")
    private String message;

    // Tier 5E: nullable - only ever populated for a SEND_COMMAND row once an
    // MQTT ACK has been correlated to it. NULL means either "not applicable"
    // (any other ActionType) or "no ACK received yet".
    @Enumerated(EnumType.STRING)
    @Column(name = "ack_status")
    private AcknowledgementStatus ackStatus;

    @Column(name = "ack_received_at")
    private Instant ackReceivedAt;

    @Column(name = "ack_message")
    private String ackMessage;

    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "ack_backend_received_at") private Instant ackBackendReceivedAt;
    @Column(name = "ack_device_timestamp") private Instant ackDeviceTimestamp;
    @Column(name = "ack_deadline_at") private Instant ackDeadlineAt;

    protected ActionHistoryRecord() {}

    public ActionHistoryRecord(UUID requestId, UUID matchId, UUID deviceId, ActionType actionType, ExecutionStatus status, Map<String, Object> parameters, Instant timestamp, String message) {
        this.requestId = requestId;
        this.matchId = matchId;
        this.deviceId = deviceId;
        this.actionType = actionType;
        this.status = status;
        this.parameters = parameters;
        this.timestamp = timestamp;
        this.message = message;
    }

    /** Used by fixtures and existing domain callers. Production MQTT ACKs use
     * a field-specific repository update so they cannot overwrite publish status. */
    public void applyAcknowledgement(AcknowledgementStatus ackStatus, Instant ackReceivedAt, String ackMessage) {
        this.ackStatus = ackStatus;
        this.ackReceivedAt = ackReceivedAt;
        this.ackMessage = ackMessage;
    }


    public static ActionHistoryRecord fromResult(ActionExecutionResult result) {
        ActionExecutionRequest req = result.request();
        UUID deviceId = null;
        if (req.parameters() != null && req.parameters().containsKey("deviceId")) {
            try {
                deviceId = UUID.fromString(req.parameters().get("deviceId").toString());
            } catch (Exception ignored) {}
        }
        return new ActionHistoryRecord(
                req.requestId(),
                req.matchId(),
                deviceId,
                req.actionType(),
                result.status(),
                req.parameters(),
                result.timestamp(),
                result.message()
        );
    }

    public ActionExecutionResult toResult() {
        ActionExecutionRequest request = new ActionExecutionRequest(requestId, matchId, actionType, parameters);
        // The executionId in ActionExecutionResult was actually just an ID for the result.
        // We'll use the requestId as the executionId to maintain the API contract.
        return new ActionExecutionResult(requestId, request, status, timestamp, message);
    }

    public UUID getRequestId() { return requestId; }
    public UUID getMatchId() { return matchId; }
    public UUID getDeviceId() { return deviceId; }
    public ActionType getActionType() { return actionType; }
    public ExecutionStatus getStatus() { return status; }
    public Map<String, Object> getParameters() { return parameters; }
    public Instant getTimestamp() { return timestamp; }
    public String getMessage() { return message; }
    public AcknowledgementStatus getAckStatus() { return ackStatus; }
    public Instant getAckReceivedAt() { return ackReceivedAt; }
    public String getAckMessage() { return ackMessage; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getAckBackendReceivedAt() { return ackBackendReceivedAt; }
    public Instant getAckDeviceTimestamp() { return ackDeviceTimestamp; }
    public Instant getAckDeadlineAt() { return ackDeadlineAt; }
}
