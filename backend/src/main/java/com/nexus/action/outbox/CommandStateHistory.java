package com.nexus.action.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only writer of {@code command_state_history} (V39, contract R06). Every fact is written in the SAME
 * transaction as the change it describes, so a rolled-back change leaves no fact and a committed change always has one.
 *
 * Sequence numbers are gapless per command. They are assigned under a per-command transaction-scoped advisory lock,
 * so two writers of the same command (the dispatcher and, in later slices, the ACK handler or the timeout scanner)
 * can never pick the same number; the primary key (command_id, sequence) is the backstop. LOCK ORDER: the advisory lock
 * is always the LAST lock a transaction takes (after the outbox row and the action_history row), so it cannot take
 * part in a cycle with the lock order outbox row, then action_history row, which the dispatcher uses.
 *
 * Never put secrets, command parameters or free-form exception text in {@code detail}; reason codes are fixed codes.
 */
@Component
public class CommandStateHistory implements OutboxTransitionRecorder {

    public enum Fact {
        ACCEPTED, QUEUED, ATTEMPT_STARTED, ATTEMPT_FAILED_TRANSIENT, ATTEMPT_UNCERTAIN, PUBLISHED, DEAD_LETTERED,
        DELIVERY_UNKNOWN, ACK_ACCEPTED, ACK_REJECTED, ACK_CONFLICT_IGNORED, ACK_TIMED_OUT, ACK_AFTER_TIMEOUT, ACK_AFTER_TERMINAL
    }

    public enum Source { ACCEPTANCE, DISPATCHER, ACK_HANDLER, TIMEOUT_SCANNER, RECONCILER, MIGRATION }

    private static final int MAX_DETAIL = 255;

    private final JdbcTemplate jdbc;

    public CommandStateHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(UUID commandId, Fact fact, String fromState, String toState, int attempt, String reasonCode,
                       Source source, String correlationId, Instant occurredAt, String detail) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("a state-history fact must be written inside the transaction of the change it describes");
        if (commandId == null || fact == null || source == null || occurredAt == null)
            throw new IllegalArgumentException("commandId, fact, source and occurredAt are required");
        String safeDetail = detail == null ? null : detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail;
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, commandId.toString());
        jdbc.update("INSERT INTO command_state_history (command_id, sequence, fact_type, from_state, to_state, attempt, reason_code,"
                        + " source, correlation_id, occurred_at, detail)"
                        + " VALUES (?, (SELECT COALESCE(MAX(sequence), 0) + 1 FROM command_state_history WHERE command_id = ?),"
                        + " ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                commandId, commandId, fact.name(), fromState, toState, attempt, reasonCode, source.name(), correlationId,
                Timestamp.from(occurredAt), safeDetail);
    }

    /** The port of P4.1: records one outbox delivery-state change as the matching fact. */
    @Override
    public void record(OutboxTransitionRecord t) {
        Fact fact;
        Source source = Source.DISPATCHER;
        if (t.from() == null) {
            fact = Fact.QUEUED;
            source = Source.ACCEPTANCE;
        } else {
            fact = switch (t.to()) {
                case IN_FLIGHT -> Fact.ATTEMPT_STARTED;
                case PUBLISHED -> Fact.PUBLISHED;
                case RETRY_WAIT -> Fact.ATTEMPT_FAILED_TRANSIENT;
                case DEAD_LETTER -> Fact.DEAD_LETTERED;
                case QUEUED -> throw new IllegalArgumentException("no transition leads to QUEUED");
            };
        }
        append(t.commandId(), fact, t.from() == null ? null : t.from().name(), t.to().name(), t.attempt(), t.reason(),
                source, t.correlationId(), t.occurredAt(), null);
    }
}
