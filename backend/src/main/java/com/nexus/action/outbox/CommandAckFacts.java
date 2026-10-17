package com.nexus.action.outbox;

import com.nexus.action.outbox.CommandStateHistory.Fact;
import com.nexus.action.outbox.CommandStateHistory.Source;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The durable, append-only ACK facts of one command (contract R13, ticket P4.4), written in the SAME transaction as the guarded ACK
 * update of {@code DeviceCommandAckMessageHandler}, so a failed write leaves neither the ACK nor a fact and the broker redelivers.
 *
 * <ul>
 * <li>The FIRST ACK appends ACK_ACCEPTED or ACK_REJECTED. When it arrives after the persisted ACK timeout was recorded it appends
 *     ACK_AFTER_TIMEOUT as well, and when it arrives after the outbox obligation ended in DEAD_LETTER, ACK_AFTER_TERMINAL as well. Nothing
 *     is erased: the timeout or terminal fact stays, and the ACK is added after it.</li>
 * <li>A conflicting duplicate appends ACK_CONFLICT_IGNORED (bounded per command, so a misbehaving device cannot grow the history without
 *     limit); an identical duplicate appends nothing.</li>
 * <li>ACK facts never change the outbox state, never move a deadline, never touch a verification or a desired state, and are never a
 *     delivery fact: an ACK says the DEVICE reported something, not that NEXUS published, converged or that anything physical happened.</li>
 * </ul>
 * Reads here take NO lock on the outbox row: the ACK path locks the device row and then the action_history row, the dispatcher locks the
 * outbox row and then the action_history row, and the history advisory lock is always last (contract R31.9), so an outbox lock here
 * could close a cycle. Correctness of the classification comes from the action_history row lock the caller already holds.
 *
 * The clock is the DATABASE clock: the receipt time compared with the persisted deadline must come from the same clock that decides the
 * deadline and the timeout.
 */
@Component
public class CommandAckFacts {

    /** At most this many conflicting duplicates are recorded per command; further ones are only counted. */
    public static final int MAX_CONFLICT_FACTS = 5;

    /** Everything the classification needs, read in one statement. */
    public record Snapshot(String outboxState, int attempt, boolean timeoutRecorded, Instant ackDeadline, int conflictFacts) {
        public boolean outboxTerminal() { return "DEAD_LETTER".equals(outboxState); }
    }

    public enum Lateness { ON_TIME, AFTER_TIMEOUT, AFTER_TERMINAL }

    private final JdbcTemplate jdbc;
    private final CommandStateHistory history;

    public CommandAckFacts(JdbcTemplate jdbc, CommandStateHistory history) {
        this.jdbc = jdbc;
        this.history = history;
    }

    /** The database clock, with microsecond precision (the same clock that sets the ACK deadline and decides the timeout). */
    public Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    public Snapshot snapshot(UUID commandId) {
        return jdbc.query("SELECT (SELECT o.state FROM command_outbox o WHERE o.command_id = a.request_id) AS outbox_state,"
                        + " COALESCE((SELECT o.attempt_count FROM command_outbox o WHERE o.command_id = a.request_id), 0) AS attempt,"
                        + " EXISTS (SELECT 1 FROM command_state_history h WHERE h.command_id = a.request_id AND h.fact_type = 'ACK_TIMED_OUT') AS timed_out,"
                        + " a.ack_deadline_at,"
                        + " (SELECT count(*) FROM command_state_history h WHERE h.command_id = a.request_id AND h.fact_type = 'ACK_CONFLICT_IGNORED') AS conflicts"
                        + " FROM action_history a WHERE a.request_id = ?",
                rs -> rs.next() ? new Snapshot(rs.getString("outbox_state"), rs.getInt("attempt"), rs.getBoolean("timed_out"),
                        rs.getTimestamp("ack_deadline_at") == null ? null : rs.getTimestamp("ack_deadline_at").toInstant(), rs.getInt("conflicts")) : null,
                commandId);
    }

    /** The committed first ACK of a command (read from the table, never from a possibly stale persistence-context entity). */
    public record FirstAck(com.nexus.action.domain.AcknowledgementStatus status, Instant deviceTimestamp, String message) {}

    public FirstAck firstAck(UUID commandId) {
        return jdbc.query("SELECT ack_status, ack_received_at, ack_message FROM action_history WHERE request_id = ? AND ack_status IS NOT NULL",
                rs -> rs.next() ? new FirstAck(com.nexus.action.domain.AcknowledgementStatus.valueOf(rs.getString(1)),
                        rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(), rs.getString(3)) : null, commandId);
    }

    /** Classifies a first ACK that was received at {@code receivedAt} (database clock). The deadline decides, not the processing time. */
    public static Lateness lateness(Snapshot s, Instant receivedAt) {
        if (s.timeoutRecorded() && s.ackDeadline() != null && !receivedAt.isBefore(s.ackDeadline())) return Lateness.AFTER_TIMEOUT;
        if (s.outboxTerminal()) return Lateness.AFTER_TERMINAL;
        return Lateness.ON_TIME;
    }

    /**
     * Appends the facts of the FIRST ACK (inside the caller's transaction).
     * @return the lateness classification that was recorded
     */
    public Lateness appendFirstAck(UUID commandId, boolean rejected, String deviceReason, Snapshot s, Instant receivedAt, String correlationId) {
        Lateness lateness = lateness(s, receivedAt);
        String outcome = rejected ? "REJECTED" : "ACCEPTED";
        String reasonCode = rejected ? code(deviceReason, "DEVICE_REJECTED") : null;
        String detail = lateness == Lateness.ON_TIME && s.timeoutRecorded()
                ? "received before the ACK deadline; the persisted timeout was recorded while this ACK was being processed" : null;
        history.append(commandId, rejected ? Fact.ACK_REJECTED : Fact.ACK_ACCEPTED, null, null, s.attempt(), reasonCode,
                Source.ACK_HANDLER, correlationId, receivedAt, detail);
        if (lateness == Lateness.AFTER_TIMEOUT)
            history.append(commandId, Fact.ACK_AFTER_TIMEOUT, null, null, s.attempt(), outcome, Source.ACK_HANDLER, correlationId, receivedAt,
                    "the device acknowledged after the persisted ACK timeout; the timeout fact is kept");
        else if (lateness == Lateness.AFTER_TERMINAL)
            history.append(commandId, Fact.ACK_AFTER_TERMINAL, null, null, s.attempt(), outcome, Source.ACK_HANDLER, correlationId, receivedAt,
                    "the device acknowledged after the delivery obligation ended in DEAD_LETTER; the command is not resent");
        return lateness;
    }

    /** Appends ACK_CONFLICT_IGNORED for a second ACK with a different content, bounded per command. @return whether a fact was written */
    public boolean appendConflict(UUID commandId, Snapshot s, Instant receivedAt, String correlationId, String firstOutcome, String laterOutcome) {
        if (s.conflictFacts() >= MAX_CONFLICT_FACTS) return false;
        history.append(commandId, Fact.ACK_CONFLICT_IGNORED, null, null, s.attempt(), code(laterOutcome, "CONFLICTING_ACK"), Source.ACK_HANDLER,
                correlationId, receivedAt, "a later ACK contradicted the first one (" + code(firstOutcome, "FIRST") + "); the first ACK is preserved");
        return true;
    }

    /** Fixed-code form of a device-supplied or internal text: uppercase letters, digits and underscore, at most 64 characters (V39). */
    public static String code(String text, String fallback) {
        if (text == null || text.isBlank()) return fallback;
        String c = text.trim().replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
        if (c.isEmpty()) return fallback;
        return c.length() > 64 ? c.substring(0, 64) : c;
    }

    /** The facts of one command in order, for diagnostics and tests. */
    public List<String> factTypes(UUID commandId) {
        return jdbc.queryForList("SELECT fact_type FROM command_state_history WHERE command_id = ? ORDER BY sequence", String.class, commandId);
    }
}
