package com.nexus.action.lifecycle;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only lifecycle of ONE command (contract R37, ledger P4-097). Everything here is a persisted fact or a pure derivation of persisted
 * facts; nothing is a prediction. Deliberately absent: the command parameters, the outbox payload and its hash, the MQTT topic, lease
 * owners and every device-supplied free text (no parameter exposure, ledger P4-101). Delivery, ACK, qualified convergence and physical
 * actuation are SEPARATE sections: the presence of one never implies another.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandLifecycleView(
        UUID commandId,
        UUID deviceId,
        UUID matchId,
        String actionType,
        /** The recorded execution status of the action history row (SUCCESS means broker publication for a command). */
        String executionStatus,
        /** Absent for a command accepted before the outbox existed or while it was disabled: nothing is invented for it. */
        Delivery delivery,
        Ack ack,
        Convergence convergence,
        Latency latency,
        /** Platform-owned statements of what the recorded facts do and do not prove. */
        List<String> explanation,
        List<Fact> facts,
        /** True when the command has more facts than the bounded list shows (never in normal operation). */
        boolean factsTruncated) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delivery(String state, int attemptCount, int maxAttempts, String terminalReason, String lastErrorCode, Instant acceptedAt,
                           Instant firstPublishConfirmedAt, Instant terminalAt) {}

    /** {@code status} is NONE, ACCEPTED or REJECTED. {@code timedOut} is the PERSISTED ACK_TIMED_OUT fact, never a computed guess. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ack(String status, boolean timedOut, Instant deadlineAt, Instant backendReceivedAt, Instant timedOutAt,
                      /** What the device claimed its own clock said. Informational only: no latency or ordering is ever derived from it. */
                      Instant deviceClaimedTimestamp) {}

    /** The qualified-readback verification of the desired state this command established, only while this command is still its owner. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Convergence(String status, Instant updatedAt, Instant deadlineAt) {}

    /** Milliseconds between BACKEND-recorded instants only; a device clock cannot influence any of them. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Latency(Long acceptedToPublishedMs, Long publishedToAckMs, Long publishedToConvergenceMs, Long acceptedToTerminalMs) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Fact(int sequence, String fact, String fromState, String toState, int attempt, String reason, String source, Instant occurredAt) {}
}
