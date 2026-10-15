package com.nexus.action.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable delivery obligation of one accepted command (V37). Identity, payload and bounds are immutable
 * ({@code updatable = false}, and enforced again by a database trigger). P4.1 provides creation and reading only;
 * the fenced state changes belong to the dispatcher of P4.2.
 */
@Entity
@Table(name = "command_outbox")
public class CommandOutboxEntity {

    @Id
    @Column(name = "command_id", updatable = false, nullable = false)
    private UUID commandId;
    @Column(name = "device_id", updatable = false, nullable = false)
    private UUID deviceId;
    @Column(name = "topic", updatable = false, nullable = false)
    private String topic;
    @Column(name = "payload_text", updatable = false, nullable = false)
    private String payloadText;
    @Column(name = "payload_sha256", updatable = false, nullable = false)
    private String payloadSha256;
    @Column(name = "payload_epoch_seconds", updatable = false, nullable = false)
    private long payloadEpochSeconds;
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private OutboxState state;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "max_attempts", updatable = false, nullable = false)
    private int maxAttempts;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Column(name = "lease_owner")
    private String leaseOwner;
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;
    @Column(name = "expires_at", updatable = false, nullable = false)
    private Instant expiresAt;
    @Column(name = "first_publish_confirmed_at")
    private Instant firstPublishConfirmedAt;
    @Column(name = "terminal_at")
    private Instant terminalAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "terminal_reason")
    private OutboxTerminalReason terminalReason;
    @Column(name = "last_error_code")
    private String lastErrorCode;
    @Column(name = "correlation_id")
    private String correlationId;
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CommandOutboxEntity() {}

    /**
     * A new obligation in state QUEUED. The expiry is the payload timestamp plus the delivery window, so queue wait
     * counts against the window (contract R11); the command id of the payload is the identity of the row.
     */
    public static CommandOutboxEntity queued(UUID commandId, UUID deviceId, OutboxPayload payload, Duration deliveryWindow,
                                             int maxAttempts, String correlationId, Instant now) {
        if (!payload.verify()) throw new IllegalArgumentException("payload hash does not match payload text");
        if (deliveryWindow.compareTo(CommandDeliveryLimits.MAX_DELIVERY_WINDOW) > 0 || deliveryWindow.compareTo(CommandDeliveryLimits.MIN_DELIVERY_WINDOW) < 0)
            throw new IllegalArgumentException("delivery window outside the permitted bounds");
        if (maxAttempts < 1 || maxAttempts > CommandDeliveryLimits.MAX_ATTEMPTS_CEILING)
            throw new IllegalArgumentException("max attempts outside the permitted bounds");
        CommandOutboxEntity e = new CommandOutboxEntity();
        e.commandId = commandId;
        e.deviceId = deviceId;
        e.topic = "nexus/devices/" + deviceId + "/commands";
        e.payloadText = payload.text();
        e.payloadSha256 = payload.sha256();
        e.payloadEpochSeconds = payload.epochSeconds();
        e.state = OutboxState.QUEUED;
        e.attemptCount = 0;
        e.maxAttempts = maxAttempts;
        e.nextAttemptAt = now;
        e.expiresAt = Instant.ofEpochSecond(payload.epochSeconds()).plus(deliveryWindow);
        e.correlationId = correlationId;
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public UUID getCommandId() { return commandId; }
    public UUID getDeviceId() { return deviceId; }
    public String getTopic() { return topic; }
    public String getPayloadText() { return payloadText; }
    public String getPayloadSha256() { return payloadSha256; }
    public long getPayloadEpochSeconds() { return payloadEpochSeconds; }
    public OutboxState getState() { return state; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getFirstPublishConfirmedAt() { return firstPublishConfirmedAt; }
    public Instant getTerminalAt() { return terminalAt; }
    public OutboxTerminalReason getTerminalReason() { return terminalReason; }
    public String getLastErrorCode() { return lastErrorCode; }
    public String getCorrelationId() { return correlationId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** The payload exactly as stored, for the dispatcher to verify and publish verbatim. */
    public OutboxPayload payload() {
        return new OutboxPayload(payloadText, payloadSha256, payloadEpochSeconds);
    }
}
