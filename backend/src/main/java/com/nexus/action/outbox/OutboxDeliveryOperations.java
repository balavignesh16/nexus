package com.nexus.action.outbox;

import com.nexus.action.outbox.CommandStateHistory.Fact;
import com.nexus.action.outbox.CommandStateHistory.Source;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every database operation of the dispatcher, each in its own short transaction (contract R07: claim T2, fenced outcome
 * T4, sweeps T5). No MQTT I/O ever happens inside any of these transactions.
 *
 * Time: claim eligibility, the delivery window and lease expiry are compared against the DATABASE clock
 * ({@code now()}, {@code clock_timestamp()}), so instances with skewed application clocks agree; the application clock
 * only supplies the timestamps that are recorded (and, at acceptance, the device-facing payload timestamp).
 *
 * Locks: an outcome or sweep locks the OUTBOX row first, then updates the action_history row, then appends its history
 * fact (advisory lock last). It never locks the device row (contract R31.9), so it cannot deadlock with acceptance
 * (device row first) or with the ACK handler (device row, then action_history row).
 *
 * Fencing: an outcome is applied only if the row is still IN_FLIGHT for the SAME lease owner and the SAME attempt
 * number it was claimed with. Otherwise nothing is written and the caller abandons the result.
 *
 * Until retries exist (P4.3) every unsuccessful attempt ends in a terminal DEAD_LETTER with an honest reason; no
 * row is ever re-claimed, parked or silently dropped. A failed command is never reported as published.
 */
@Component
public class OutboxDeliveryOperations {

    /** What the dispatcher observed for one attempt. */
    public enum AttemptResult { CONFIRMED, NOT_SENT, UNCERTAIN, PAYLOAD_INTEGRITY }

    public enum Disposition { PUBLISHED, DEAD_LETTERED, FENCED }

    public record Claimed(UUID commandId, UUID deviceId, String topic, OutboxPayload payload, int attempt, int maxAttempts,
                          String leaseOwner, String correlationId) {}

    /**
     * The committed result of one outcome or sweep.
     * @param actionHistoryUpdated true when this transaction changed the ActionHistory row (it was still ACCEPTED_FOR_DISPATCH)
     */
    public record Resolution(Disposition disposition, UUID commandId, UUID deviceId, OutboxTerminalReason reason,
                             String errorCode, String message, Instant at, boolean actionHistoryUpdated, String correlationId,
                             boolean ackRecorded) {
        public Resolution(Disposition disposition, UUID commandId, UUID deviceId, OutboxTerminalReason reason,
                          String errorCode, String message, Instant at, boolean actionHistoryUpdated, String correlationId) {
            this(disposition, commandId, deviceId, reason, errorCode, message, at, actionHistoryUpdated, correlationId, false);
        }
        public boolean published() { return disposition == Disposition.PUBLISHED; }
        public boolean deadLettered() { return disposition == Disposition.DEAD_LETTERED; }
        /** The delivery outcome is UNCERTAIN: the command may still reach, or already have reached, the device. */
        public boolean uncertain() { return deadLettered() && reason == OutboxTerminalReason.UNCERTAIN_EXPIRED; }
    }

    /** A queued command that is being held behind an earlier command whose delivery outcome was uncertain. */
    public record Held(UUID commandId, UUID deviceId, String correlationId, Instant holdUntil) {}

    public static final String MSG_PUBLISHED = "Command published to MQTT";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CommandStateHistory history;
    private final Clock clock;
    private final Duration ackTimeout;

    @Autowired
    public OutboxDeliveryOperations(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, CommandStateHistory history,
                                    Clock clock, @Value("${nexus.command.ack-timeout:PT30S}") Duration ackTimeout) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.history = history;
        this.clock = clock;
        this.ackTimeout = ackTimeout;
    }

    // ----------------------------------------------------------------------------------------------- claim (T2)

    /**
     * Commands that may be claimed now, oldest first. A row is a candidate only if it is QUEUED, due, NOT expired
     * (database clock) and nothing older is unresolved for the same device: an older QUEUED or RETRY_WAIT command
     * blocks a newer one, and a device with an IN_FLIGHT command has no candidate. This only SELECTS; the claim
     * itself re-checks every condition atomically.
     */
    public List<UUID> candidates(int limit) {
        return jdbc.queryForList("SELECT o.command_id FROM command_outbox o"
                + " WHERE o.state = 'QUEUED' AND o.next_attempt_at <= now() AND now() < o.expires_at"
                + " AND " + DEVICE_GUARDS
                + " ORDER BY o.created_at, o.command_id LIMIT ?", UUID.class, limit);
    }

    private static final String NO_OLDER_UNRESOLVED = "NOT EXISTS (SELECT 1 FROM command_outbox x WHERE x.device_id = o.device_id"
            + " AND x.command_id <> o.command_id AND (x.state IN ('IN_FLIGHT', 'RETRY_WAIT')"
            + " OR (x.state = 'QUEUED' AND x.created_at < o.created_at)))";

    private static final long HOLD_SECONDS = CommandDeliveryLimits.UNCERTAIN_DELIVERY_HOLD.toSeconds();
    private static final long HOLD_CAP_SECONDS = CommandDeliveryLimits.UNCERTAIN_DELIVERY_HOLD_CAP.toSeconds();

    /**
     * The end of the hold an uncertain command {@code u} places on later commands of its device: the firmware freshness window
     * plus the clock/network margin after the uncertain command's payload timestamp (a late copy can be applied until then),
     * but never more than the cap after its creation on the database clock.
     */
    private static final String HOLD_UNTIL = "LEAST(to_timestamp(u.payload_epoch_seconds) + INTERVAL '" + HOLD_SECONDS + " seconds',"
            + " u.created_at + INTERVAL '" + HOLD_CAP_SECONDS + " seconds')";

    /**
     * No uncertain predecessor of the same device can still reach the device: an earlier command that ended UNCERTAIN_EXPIRED
     * holds the device until its freshness horizon has passed on the database clock, whatever ACK may have arrived (a duplicate
     * of the same message would still be applied). Persisted in the outbox row itself, so it survives restarts and is the same
     * for every instance.
     */
    private static final String NO_UNCERTAIN_HOLD = "NOT EXISTS (SELECT 1 FROM command_outbox u WHERE u.device_id = o.device_id"
            + " AND u.state = 'DEAD_LETTER' AND u.terminal_reason = 'UNCERTAIN_EXPIRED' AND now() < " + HOLD_UNTIL + ")";

    private static final String DEVICE_GUARDS = NO_OLDER_UNRESOLVED + " AND " + NO_UNCERTAIN_HOLD;

    /** True while any obligation is unresolved. A cheap indexed probe so an idle (or rolled-back) system does no further work. */
    public boolean hasWork() {
        Boolean any = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM command_outbox WHERE state IN ('QUEUED', 'IN_FLIGHT', 'RETRY_WAIT'))", Boolean.class);
        return Boolean.TRUE.equals(any);
    }

    /**
     * Records, once per command, that a QUEUED command is being held behind an earlier command whose delivery outcome was
     * uncertain: a state-history fact (QUEUED to QUEUED, reason HELD_UNCERTAIN_PREDECESSOR) and an ActionHistory message so the
     * hold is visible. It changes no state and sends nothing.
     */
    public List<Held> markHeld(int limit) {
        return tx.execute(status -> {
            Instant at = clock.instant();
            List<Object[]> rows = jdbc.query("SELECT o.command_id, o.device_id, o.correlation_id, (SELECT max(" + HOLD_UNTIL + ") FROM command_outbox u"
                            + " WHERE u.device_id = o.device_id AND u.state = 'DEAD_LETTER' AND u.terminal_reason = 'UNCERTAIN_EXPIRED') AS hold_until"
                            + " FROM command_outbox o WHERE o.state = 'QUEUED' AND now() < o.expires_at AND NOT " + NO_UNCERTAIN_HOLD
                            + " AND NOT EXISTS (SELECT 1 FROM command_state_history h WHERE h.command_id = o.command_id"
                            + " AND h.reason_code = 'HELD_UNCERTAIN_PREDECESSOR')"
                            + " ORDER BY o.created_at LIMIT ? FOR UPDATE OF o SKIP LOCKED",
                    (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getTimestamp(4).toInstant()}, limit);
            List<Held> held = new ArrayList<>();
            for (Object[] r : rows) {
                UUID command = (UUID) r[0];
                Instant until = (Instant) r[3];
                jdbc.update("UPDATE action_history SET message = ? WHERE request_id = ? AND status = 'ACCEPTED_FOR_DISPATCH'",
                        "Held: an earlier command for this device has an uncertain delivery outcome; this command is not sent before "
                                + until + " so it cannot be applied out of order", command);
                history.append(command, Fact.QUEUED, OutboxState.QUEUED.name(), OutboxState.QUEUED.name(), 0, "HELD_UNCERTAIN_PREDECESSOR",
                        Source.DISPATCHER, (String) r[2], at, "held until " + until);
                held.add(new Held(command, (UUID) r[1], (String) r[2], until));
            }
            return held;
        });
    }

    /**
     * Claims one command: QUEUED to IN_FLIGHT, attempt incremented, lease set, ATTEMPT_STARTED recorded, committed before
     * any I/O. Empty when another worker won (row locked or already claimed), when the row is no longer eligible, or when
     * the single-in-flight index refused the claim (SQLSTATE 23505, a normal outcome and not an error).
     */
    public Optional<Claimed> claim(UUID commandId, String leaseOwner, Duration lease) {
        try {
            return tx.execute(status -> {
                Instant now = clock.instant();
                List<Claimed> rows = jdbc.query("UPDATE command_outbox SET state = 'IN_FLIGHT', attempt_count = attempt_count + 1,"
                                + " next_attempt_at = NULL, lease_owner = ?, lease_expires_at = now() + make_interval(secs => ?), updated_at = ?"
                                + " WHERE command_id = (SELECT o.command_id FROM command_outbox o WHERE o.command_id = ?"
                                + " AND o.state = 'QUEUED' AND o.next_attempt_at <= now() AND now() < o.expires_at AND " + DEVICE_GUARDS
                                + " FOR UPDATE SKIP LOCKED)"
                                + " RETURNING device_id, topic, payload_text, payload_sha256, payload_epoch_seconds, attempt_count, max_attempts, correlation_id",
                        (rs, i) -> new Claimed(commandId, rs.getObject("device_id", UUID.class), rs.getString("topic"),
                                new OutboxPayload(rs.getString("payload_text"), rs.getString("payload_sha256"), rs.getLong("payload_epoch_seconds")),
                                rs.getInt("attempt_count"), rs.getInt("max_attempts"), leaseOwner, rs.getString("correlation_id")),
                        leaseOwner, lease.toMillis() / 1000.0, Timestamp.from(now), commandId);
                if (rows.isEmpty()) return Optional.<Claimed>empty();
                Claimed claimed = rows.get(0);
                history.record(new OutboxTransitionRecord(commandId, OutboxState.QUEUED, OutboxState.IN_FLIGHT, claimed.attempt(),
                        "CLAIMED", claimed.correlationId(), now));
                return Optional.of(claimed);
            });
        } catch (DataIntegrityViolationException e) {
            if (isUniqueViolation(e)) return Optional.empty();
            throw e;
        }
    }

    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof SQLException sql && "23505".equals(sql.getSQLState())) return true;
        return false;
    }

    // -------------------------------------------------------------------------------------------- outcome (T4)

    /**
     * Records the outcome of one attempt, fenced on (lease owner, attempt). FENCED means this worker no longer owns the
     * claim (lease recovered, row already resolved) and NOTHING was written.
     */
    public Resolution complete(Claimed claimed, AttemptResult result, String code) {
        return tx.execute(status -> {
            Instant at = clock.instant();
            // Lock the outbox row (fence) first; the window is judged on the database clock at this instant.
            List<Boolean> inWindow = jdbc.query("SELECT (clock_timestamp() < expires_at) FROM command_outbox"
                            + " WHERE command_id = ? AND state = 'IN_FLIGHT' AND lease_owner = ? AND attempt_count = ? FOR UPDATE",
                    (rs, i) -> rs.getBoolean(1), claimed.commandId(), claimed.leaseOwner(), claimed.attempt());
            if (inWindow.isEmpty())
                return new Resolution(Disposition.FENCED, claimed.commandId(), claimed.deviceId(), null, null, null, at, false, claimed.correlationId());
            if (result == AttemptResult.CONFIRMED && inWindow.get(0)) return publish(claimed, at);
            if (result == AttemptResult.CONFIRMED) {
                // Completed after expires_at: recorded as late and uncertain, never as a normal publication (R31.4).
                return deadLetter(claimed.commandId(), claimed.deviceId(), claimed.attempt(), claimed.correlationId(), at,
                        OutboxTerminalReason.UNCERTAIN_EXPIRED, "PUBLISH_COMPLETED_AFTER_EXPIRY", true,
                        "DELIVERY_UNCONFIRMED: the publish completed after the delivery window had expired; the device may reject the command as stale"
                                + " and NEXUS does not resend it");
            }
            return switch (result) {
                case NOT_SENT -> deadLetter(claimed.commandId(), claimed.deviceId(), claimed.attempt(), claimed.correlationId(), at,
                        OutboxTerminalReason.PERMANENT_FAILURE, "NOT_SENT_" + safe(code), false,
                        "MQTT publish failed: the command was not sent (" + safe(code) + "); automatic retry is not available in this release");
                case UNCERTAIN -> deadLetter(claimed.commandId(), claimed.deviceId(), claimed.attempt(), claimed.correlationId(), at,
                        OutboxTerminalReason.UNCERTAIN_EXPIRED, safe(code), true,
                        "DELIVERY_UNCONFIRMED: the broker did not confirm the publish (" + safe(code)
                                + "); the command may or may not have reached the device and NEXUS does not resend it");
                case PAYLOAD_INTEGRITY -> deadLetter(claimed.commandId(), claimed.deviceId(), claimed.attempt(), claimed.correlationId(), at,
                        OutboxTerminalReason.PAYLOAD_INTEGRITY, "PAYLOAD_HASH_MISMATCH", false,
                        "Command payload failed its integrity check and was not sent (PAYLOAD_INTEGRITY)");
                case CONFIRMED -> throw new IllegalStateException("handled above");
            };
        });
    }

    private Resolution publish(Claimed c, Instant at) {
        jdbc.update("UPDATE command_outbox SET state = 'PUBLISHED', lease_owner = NULL, lease_expires_at = NULL,"
                        + " first_publish_confirmed_at = ?, terminal_at = ?, last_error_code = NULL, updated_at = ? WHERE command_id = ?",
                Timestamp.from(at), Timestamp.from(at), Timestamp.from(at), c.commandId());
        int updated = jdbc.update("UPDATE action_history SET status = 'SUCCESS', \"timestamp\" = ?, message = ?, published_at = ?, ack_deadline_at = ?"
                        + " WHERE request_id = ? AND status = 'ACCEPTED_FOR_DISPATCH'",
                Timestamp.from(at), MSG_PUBLISHED, Timestamp.from(at), Timestamp.from(at.plus(ackTimeout)), c.commandId());
        history.record(new OutboxTransitionRecord(c.commandId(), OutboxState.IN_FLIGHT, OutboxState.PUBLISHED, c.attempt(),
                "BROKER_CONFIRMED", c.correlationId(), at));
        return new Resolution(Disposition.PUBLISHED, c.commandId(), c.deviceId(), null, null, MSG_PUBLISHED, at, updated == 1, c.correlationId(), ackRecorded(c.commandId()));
    }

    /**
     * IN_FLIGHT (or QUEUED/RETRY_WAIT for an expiry) to DEAD_LETTER. The caller already holds the outbox row lock in this
     * transaction. {@code uncertain} additionally appends ATTEMPT_UNCERTAIN so the history never calls an uncertain
     * attempt a plain failure.
     */
    private Resolution deadLetter(UUID commandId, UUID deviceId, int attempt, String correlationId, Instant at,
                                  OutboxTerminalReason reason, String errorCode, boolean uncertain, String message) {
        jdbc.update("UPDATE command_outbox SET state = 'DEAD_LETTER', next_attempt_at = NULL, lease_owner = NULL, lease_expires_at = NULL,"
                        + " terminal_at = ?, terminal_reason = ?, last_error_code = ?, updated_at = ? WHERE command_id = ?",
                Timestamp.from(at), reason.name(), code64(errorCode), Timestamp.from(at), commandId);
        return finishDeadLetter(commandId, deviceId, attempt, correlationId, at, reason, errorCode, uncertain, message,
                attempt > 0 ? OutboxState.IN_FLIGHT : OutboxState.QUEUED);
    }

    private Resolution finishDeadLetter(UUID commandId, UUID deviceId, int attempt, String correlationId, Instant at,
                                        OutboxTerminalReason reason, String errorCode, boolean uncertain, String message, OutboxState from) {
        int updated = jdbc.update("UPDATE action_history SET status = 'FAILURE', \"timestamp\" = ?, message = ?"
                        + " WHERE request_id = ? AND status = 'ACCEPTED_FOR_DISPATCH'",
                Timestamp.from(at), message, commandId);
        if (uncertain)
            history.append(commandId, Fact.ATTEMPT_UNCERTAIN, from.name(), from.name(), attempt, code64(errorCode), Source.DISPATCHER, correlationId, at, null);
        history.append(commandId, Fact.DEAD_LETTERED, from.name(), OutboxState.DEAD_LETTER.name(), attempt, reason.name(),
                Source.DISPATCHER, correlationId, at, code64(errorCode));
        return new Resolution(Disposition.DEAD_LETTERED, commandId, deviceId, reason, errorCode, message, at, updated == 1, correlationId, ackRecorded(commandId));
    }

    /** Whether the device has already acknowledged this command (read inside the outcome transaction; the ACK itself is never written here). */
    private boolean ackRecorded(UUID commandId) {
        Boolean ack = jdbc.queryForObject("SELECT ack_status IS NOT NULL FROM action_history WHERE request_id = ?", Boolean.class, commandId);
        return Boolean.TRUE.equals(ack);
    }

    private static String safe(String code) {
        return code == null ? "UNSPECIFIED" : code.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(java.util.Locale.ROOT);
    }

    /** Fixed-code columns are VARCHAR(64) and uppercase (V37, V39). */
    private static String code64(String code) {
        String c = safe(code);
        return c.length() > 64 ? c.substring(0, 64) : c;
    }

    // ---------------------------------------------------------------------------------------------- sweeps (T5)

    /**
     * Dead-letters QUEUED (and, later, RETRY_WAIT) commands whose delivery window ended on the database clock. They were
     * never sent and are never resent with a refreshed timestamp. Without this an expired command would sit at the head
     * of its device queue and block every newer command forever.
     */
    public List<Resolution> expireDue(int limit) {
        return tx.execute(status -> {
            Instant at = clock.instant();
            // a command that was held behind an uncertain predecessor (hold fact recorded, or hold still active) says so
            List<Object[]> rows = jdbc.query("UPDATE command_outbox o SET state = 'DEAD_LETTER', next_attempt_at = NULL, terminal_at = ?,"
                            + " terminal_reason = 'EXPIRED', updated_at = ?, last_error_code = CASE WHEN EXISTS (SELECT 1 FROM command_state_history h"
                            + " WHERE h.command_id = o.command_id AND h.reason_code = 'HELD_UNCERTAIN_PREDECESSOR') OR NOT " + NO_UNCERTAIN_HOLD
                            + " THEN 'HELD_BY_UNCERTAIN_PREDECESSOR' ELSE 'DELIVERY_WINDOW_EXPIRED' END"
                            + " WHERE o.command_id IN (SELECT command_id FROM command_outbox WHERE state IN ('QUEUED', 'RETRY_WAIT')"
                            + " AND now() >= expires_at ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED)"
                            + " RETURNING o.command_id, o.device_id, o.attempt_count, o.correlation_id, o.last_error_code",
                    (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4), rs.getString(5)},
                    Timestamp.from(at), Timestamp.from(at), limit);
            List<Resolution> out = new ArrayList<>();
            for (Object[] r : rows) {
                boolean held = "HELD_BY_UNCERTAIN_PREDECESSOR".equals(r[4]);
                out.add(finishDeadLetter((UUID) r[0], (UUID) r[1], (Integer) r[2], (String) r[3], at, OutboxTerminalReason.EXPIRED,
                        (String) r[4], false,
                        held ? "Command expired before delivery (EXPIRED): it was held behind an earlier command whose delivery outcome was uncertain,"
                                + " and its delivery window ended before the hold did; it was never sent"
                             : "Command expired before delivery (EXPIRED): the delivery window ended and the command was never sent",
                        (Integer) r[2] > 0 ? OutboxState.RETRY_WAIT : OutboxState.QUEUED));
            }
            return out;
        });
    }

    /**
     * Recovers IN_FLIGHT rows whose lease ended on the database clock (a crashed or stalled worker). The publish may have
     * reached the broker, so the attempt is UNCERTAIN, never a plain failure and never a success. Without retries (P4.3)
     * the row becomes DEAD_LETTER UNCERTAIN_EXPIRED; it is not re-claimed, so a lost worker cannot cause repeated sends.
     * The original worker, if it is still alive, is fenced out when it reports.
     */
    public List<Resolution> recoverExpiredLeases(int limit) {
        return tx.execute(status -> {
            Instant at = clock.instant();
            List<Object[]> rows = jdbc.query("UPDATE command_outbox SET state = 'DEAD_LETTER', lease_owner = NULL, lease_expires_at = NULL,"
                            + " terminal_at = ?, terminal_reason = 'UNCERTAIN_EXPIRED', last_error_code = 'LEASE_EXPIRED_UNCERTAIN', updated_at = ?"
                            + " WHERE command_id IN (SELECT command_id FROM command_outbox WHERE state = 'IN_FLIGHT' AND lease_expires_at < now()"
                            + " ORDER BY lease_expires_at LIMIT ? FOR UPDATE SKIP LOCKED)"
                            + " RETURNING command_id, device_id, attempt_count, correlation_id",
                    (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4)},
                    Timestamp.from(at), Timestamp.from(at), limit);
            List<Resolution> out = new ArrayList<>();
            for (Object[] r : rows)
                out.add(finishDeadLetter((UUID) r[0], (UUID) r[1], (Integer) r[2], (String) r[3], at, OutboxTerminalReason.UNCERTAIN_EXPIRED,
                        "LEASE_EXPIRED_UNCERTAIN", true,
                        "DELIVERY_UNCONFIRMED: the dispatcher lost its lease before the outcome was recorded; the command may or may not"
                                + " have reached the device and NEXUS does not resend it",
                        OutboxState.IN_FLIGHT));
            return out;
        });
    }
}
