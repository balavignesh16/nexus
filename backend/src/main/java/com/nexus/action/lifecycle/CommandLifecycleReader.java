package com.nexus.action.lifecycle;

import com.nexus.action.lifecycle.CommandLifecycleView.Ack;
import com.nexus.action.lifecycle.CommandLifecycleView.Convergence;
import com.nexus.action.lifecycle.CommandLifecycleView.Delivery;
import com.nexus.action.lifecycle.CommandLifecycleView.Fact;
import com.nexus.action.lifecycle.CommandLifecycleView.Latency;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Read-only queries behind the lifecycle API (contract R37, ledger P4-097, P4-101, P4-103). Only SELECT statements run here, in a
 * read-only transaction, and every list is bounded: a command shows at most {@value #MAX_FACTS} facts, a dead-letter page at most
 * {@value #MAX_PAGE} items. The selected columns are an allow-list: command parameters, the outbox payload and its hash, the topic, lease
 * owners and device-supplied free text are never read, so they cannot be exposed by a later change to a response type.
 */
@Component
public class CommandLifecycleReader {
    public static final int MAX_FACTS = 100;
    public static final int MAX_PAGE = 100;
    public static final int DEFAULT_PAGE = 25;
    private static final Pattern CURSOR = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z)\\|([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

    private final JdbcTemplate jdbc;

    @Autowired
    public CommandLifecycleReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One dead-lettered command. A dead letter is a DELIVERY outcome; an ACK timeout is a different fact and is never listed here. */
    public record DeadLetter(UUID commandId, UUID deviceId, String actionType, String terminalReason, String lastErrorCode, int attemptCount,
                             int maxAttempts, Instant acceptedAt, Instant terminalAt, boolean uncertain, boolean acknowledged) {}

    public record DeadLetterPage(List<DeadLetter> items, String nextCursor) {}

    @Transactional(readOnly = true)
    public Optional<CommandLifecycleView> find(UUID commandId) {
        List<Row> rows = jdbc.query(
                "SELECT a.request_id, a.device_id, a.match_id, a.action_type, a.status, a.ack_status, a.ack_backend_received_at,"
                        + " COALESCE(a.ack_device_timestamp, a.ack_received_at) AS ack_device_claimed, a.published_at, a.ack_deadline_at,"
                        + " o.state, o.attempt_count, o.max_attempts, o.terminal_reason, o.last_error_code, o.created_at, o.first_publish_confirmed_at, o.terminal_at,"
                        + " v.verification_status, v.verification_updated_at, v.verification_deadline_at"
                        + " FROM action_history a"
                        + " LEFT JOIN command_outbox o ON o.command_id = a.request_id"
                        + " LEFT JOIN current_twin_verification_state v ON v.desired_command_id = a.request_id"
                        + " WHERE a.request_id = ?",
                (rs, n) -> new Row(rs.getObject("request_id", UUID.class), rs.getObject("device_id", UUID.class), rs.getObject("match_id", UUID.class),
                        rs.getString("action_type"), rs.getString("status"), rs.getString("ack_status"), instant(rs.getTimestamp("ack_backend_received_at")),
                        instant(rs.getTimestamp("ack_device_claimed")), instant(rs.getTimestamp("published_at")), instant(rs.getTimestamp("ack_deadline_at")),
                        rs.getString("state"), rs.getInt("attempt_count"), rs.getInt("max_attempts"), rs.getString("terminal_reason"),
                        rs.getString("last_error_code"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("first_publish_confirmed_at")),
                        instant(rs.getTimestamp("terminal_at")), rs.getString("verification_status"), instant(rs.getTimestamp("verification_updated_at")),
                        instant(rs.getTimestamp("verification_deadline_at"))),
                commandId);
        if (rows.isEmpty()) return Optional.empty();
        Row r = rows.get(0);

        List<Fact> facts = new ArrayList<>(jdbc.query(
                "SELECT sequence, fact_type, from_state, to_state, attempt, reason_code, source, occurred_at FROM command_state_history"
                        + " WHERE command_id = ? ORDER BY sequence LIMIT " + (MAX_FACTS + 1),
                (rs, n) -> new Fact(rs.getInt("sequence"), rs.getString("fact_type"), rs.getString("from_state"), rs.getString("to_state"),
                        rs.getInt("attempt"), LifecycleReasonCodes.visible(rs.getString("source"), rs.getString("reason_code")), rs.getString("source"),
                        instant(rs.getTimestamp("occurred_at"))),
                commandId));
        boolean truncated = facts.size() > MAX_FACTS;
        if (truncated) facts = new ArrayList<>(facts.subList(0, MAX_FACTS));

        Instant timedOutAt = facts.stream().filter(f -> "ACK_TIMED_OUT".equals(f.fact())).map(Fact::occurredAt).findFirst().orElse(null);
        boolean hasDelivery = r.outboxState() != null;
        Delivery delivery = hasDelivery ? new Delivery(r.outboxState(), r.attemptCount(), r.maxAttempts(), r.terminalReason(), r.lastErrorCode(),
                r.acceptedAt(), r.firstPublishConfirmedAt(), r.terminalAt()) : null;
        Ack ack = new Ack(r.ackStatus() == null ? "NONE" : r.ackStatus(), timedOutAt != null, r.ackDeadlineAt(), r.ackBackendReceivedAt(), timedOutAt,
                r.ackDeviceClaimed());
        Convergence convergence = r.verificationStatus() == null ? null
                : new Convergence(r.verificationStatus(), r.verificationUpdatedAt(), r.verificationDeadlineAt());
        // Backend instants only. published_at and ack_backend_received_at are both written from the backend/database clock; the device's own
        // timestamp (ack.deviceClaimedTimestamp) takes no part in any figure.
        Latency latency = new Latency(
                millis(r.acceptedAt(), r.firstPublishConfirmedAt()),
                millis(r.publishedAt(), r.ackBackendReceivedAt()),
                "CONVERGED".equals(r.verificationStatus()) ? millis(r.publishedAt(), r.verificationUpdatedAt()) : null,
                millis(r.acceptedAt(), r.terminalAt()));
        boolean ackedAfterTimeout = facts.stream().anyMatch(f -> "ACK_AFTER_TIMEOUT".equals(f.fact()));
        boolean deliveryUnknown = facts.stream().anyMatch(f -> "DELIVERY_UNKNOWN".equals(f.fact()));
        boolean attemptUncertain = facts.stream().anyMatch(f -> "ATTEMPT_UNCERTAIN".equals(f.fact()));
        return Optional.of(new CommandLifecycleView(r.commandId(), r.deviceId(), r.matchId(), r.actionType(), r.executionStatus(), delivery, ack, convergence,
                latency, CommandLifecycleStatements.of(r.actionType(), delivery, ack, convergence, ackedAfterTimeout, deliveryUnknown, attemptUncertain),
                facts, truncated));
    }

    /** The commands among {@code ids} that carry a DELIVERY_UNKNOWN fact (accepted before the outbox existed; never replayed). One bounded query. */
    @Transactional(readOnly = true)
    public java.util.Set<UUID> deliveryUnknownAmong(java.util.Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return java.util.Set.of();
        return new java.util.HashSet<>(jdbc.queryForList("SELECT DISTINCT command_id FROM command_state_history WHERE fact_type = 'DELIVERY_UNKNOWN' AND command_id = ANY (?)",
                UUID.class, (Object) ids.toArray(new UUID[0])));
    }

    /** Keyset-paginated dead letters, newest first. {@code cursor} is the opaque value of a previous page; a malformed one is rejected. */
    @Transactional(readOnly = true)
    public DeadLetterPage deadLetters(int limit, String cursor) {
        if (limit < 1 || limit > MAX_PAGE) throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE);
        Instant beforeAt = null;
        UUID beforeId = null;
        if (cursor != null && !cursor.isEmpty()) {
            var m = CURSOR.matcher(cursor);
            if (!m.matches()) throw new IllegalArgumentException("cursor is not a value returned by this API");
            try {
                beforeAt = Instant.parse(m.group(1));
                beforeId = UUID.fromString(m.group(2));
            } catch (RuntimeException malformed) {
                throw new IllegalArgumentException("cursor is not a value returned by this API");
            }
        }
        String keyset = beforeAt == null ? "" : " AND (o.terminal_at, o.command_id) < (?, ?)";
        Object[] args = beforeAt == null ? new Object[0] : new Object[] {Timestamp.from(beforeAt), beforeId};
        List<DeadLetter> rows = new ArrayList<>(jdbc.query(
                "SELECT o.command_id, o.device_id, a.action_type, o.terminal_reason, o.last_error_code, o.attempt_count, o.max_attempts, o.created_at,"
                        + " o.terminal_at, (a.ack_status IS NOT NULL) AS acknowledged"
                        + " FROM command_outbox o JOIN action_history a ON a.request_id = o.command_id"
                        + " WHERE o.state = 'DEAD_LETTER' AND o.terminal_at IS NOT NULL" + keyset
                        + " ORDER BY o.terminal_at DESC, o.command_id DESC LIMIT " + (limit + 1),
                (rs, n) -> new DeadLetter(rs.getObject("command_id", UUID.class), rs.getObject("device_id", UUID.class), rs.getString("action_type"),
                        rs.getString("terminal_reason"), rs.getString("last_error_code"), rs.getInt("attempt_count"), rs.getInt("max_attempts"),
                        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("terminal_at")),
                        "UNCERTAIN_EXPIRED".equals(rs.getString("terminal_reason")), rs.getBoolean("acknowledged")),
                args));
        String next = null;
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
            DeadLetter last = rows.get(rows.size() - 1);
            next = last.terminalAt() + "|" + last.commandId();
        }
        return new DeadLetterPage(rows, next);
    }

    private static Long millis(Instant from, Instant to) {
        if (from == null || to == null) return null;
        long ms = Duration.between(from, to).toMillis();
        return ms < 0 ? null : ms;      // an inverted pair is not a latency: report nothing instead of a negative figure
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private record Row(UUID commandId, UUID deviceId, UUID matchId, String actionType, String executionStatus, String ackStatus,
                       Instant ackBackendReceivedAt, Instant ackDeviceClaimed, Instant publishedAt, Instant ackDeadlineAt, String outboxState,
                       int attemptCount, int maxAttempts, String terminalReason, String lastErrorCode, Instant acceptedAt,
                       Instant firstPublishConfirmedAt, Instant terminalAt, String verificationStatus, Instant verificationUpdatedAt,
                       Instant verificationDeadlineAt) {}
}
