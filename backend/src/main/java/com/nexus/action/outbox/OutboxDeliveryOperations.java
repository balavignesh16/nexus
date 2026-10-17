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

    public enum Disposition { PUBLISHED, DEAD_LETTERED, FENCED, RETRY_SCHEDULED }

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
        /** The attempt was proven not sent and the command was parked in RETRY_WAIT; nothing is terminal and no failure signal is due. */
        public boolean retryScheduled() { return disposition == Disposition.RETRY_SCHEDULED; }
        /** The delivery outcome is UNCERTAIN: the command may still reach, or already have reached, the device. */
        public boolean uncertain() { return deadLettered() && reason == OutboxTerminalReason.UNCERTAIN_EXPIRED; }
    }

    /**
     * A proven-not-sent dead letter whose follow-up signals may be missing.
     * @param twinPending the Twin verification of this command is still PENDING, so telling it is still needed; the device row is locked
     *     for that call, so it is made ONLY when needed (never on every scan of an outcome whose only missing signal is a rule fact nobody subscribes to)
     */
    public record Unreacted(Resolution resolution, boolean twinPending) {}

    /** A queued command that is being held behind an earlier command whose delivery outcome was uncertain. */
    public record Held(UUID commandId, UUID deviceId, String correlationId, Instant holdUntil) {}

    public static final String MSG_PUBLISHED = "Command published to MQTT";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CommandStateHistory history;
    private final Clock clock;
    private final Duration ackTimeout;
    private final OutboxRetryPolicy retryPolicy;

    @Autowired
    public OutboxDeliveryOperations(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, CommandStateHistory history,
                                    Clock clock, @Value("${nexus.command.ack-timeout:PT30S}") Duration ackTimeout, CommandOutboxSettings settings) {
        this(jdbc, transactionManager, history, clock, ackTimeout,
                new OutboxRetryPolicy(settings, () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble()));
    }

    /** The default retry configuration (the shipped defaults, outbox flag irrelevant here): for tests that do not care about backoff values. */
    public OutboxDeliveryOperations(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, CommandStateHistory history,
                                    Clock clock, Duration ackTimeout) {
        this(jdbc, transactionManager, history, clock, ackTimeout, new CommandOutboxSettings(false, Duration.ofSeconds(15), 3, Duration.ofSeconds(3),
                Duration.ofMillis(500), 2.0, Duration.ofSeconds(4), 0.2, Duration.ofSeconds(6), 10, Duration.ofMillis(250), Duration.ofSeconds(1), 4));
    }

    /** @param retryPolicy carries the backoff configuration and the jitter source; tests inject a deterministic source */
    public OutboxDeliveryOperations(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, CommandStateHistory history,
                                    Clock clock, Duration ackTimeout, OutboxRetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.history = history;
        this.clock = clock;
        this.ackTimeout = ackTimeout;
    }

    // ----------------------------------------------------------------------------------------------- claim (T2)

    /**
     * Commands that may be claimed now, oldest first. A row is a candidate only if it is QUEUED or RETRY_WAIT, due
     * ({@code next_attempt_at}, a retry's backoff end), NOT expired (database clock) and nothing else is unresolved ahead of it for the
     * same device: an older QUEUED command or ANY RETRY_WAIT command of another id blocks it, and a device with an IN_FLIGHT command
     * has no candidate. So a command waiting for its retry also blocks every later command of its device. This only SELECTS; the claim
     * itself re-checks every condition atomically.
     */
    public List<UUID> candidates(int limit) {
        return jdbc.queryForList("SELECT o.command_id FROM command_outbox o"
                + " WHERE o.state IN ('QUEUED', 'RETRY_WAIT') AND o.next_attempt_at <= now() AND now() < o.expires_at"
                + " AND " + NOT_ACKNOWLEDGED + " AND " + DEVICE_GUARDS
                + " ORDER BY o.created_at, o.command_id LIMIT ?", UUID.class, limit);
    }

    /**
     * A command that a device has already acknowledged is never published (again): the ACK is authoritative evidence that the device holds the
     * command, whatever the delivery record says. The sweep resolves such a row ({@link #resolveAcknowledgedUnpublished}).
     */
    private static final String NOT_ACKNOWLEDGED = "NOT EXISTS (SELECT 1 FROM action_history ak WHERE ak.request_id = o.command_id AND ak.ack_status IS NOT NULL)";

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
        return tryClaim(commandId, leaseOwner, lease).claimed();
    }

    /** Outcome of one claim attempt: the claim, or why there is none. {@code rateLimited} means the publish gate had no free permit. */
    public record ClaimResult(Optional<Claimed> claimed, boolean rateLimited, long retryAfterMillis) {
        static ClaimResult none() { return new ClaimResult(Optional.empty(), false, 0); }
    }

    /**
     * The publish gate (contract R18) when one is wired: every claim passes it, so no attempt can start without a permit. Optional only for
     * the narrow unit tests that build this class without Spring; the production dispatcher always has it.
     */
    private CommandPublishGate publishGate;

    @Autowired(required = false)
    void setPublishGate(CommandPublishGate publishGate) { this.publishGate = publishGate; }

    /**
     * {@link #claim} with the reason for an empty result. The permit decision and the claim are ONE transaction under the gate's advisory
     * lock; when the gate refuses, nothing is changed: the row keeps its state, attempt count, payload, timestamp and deadline. A failure of
     * the gate itself propagates (fail closed): no claim, hence no publication.
     */
    public ClaimResult tryClaim(UUID commandId, String leaseOwner, Duration lease) {
        try {
            return tx.execute(status -> {
                if (publishGate != null) {
                    CommandPublishGate.Decision permit = publishGate.permit();
                    if (!permit.allowed()) return new ClaimResult(Optional.empty(), true, permit.retryAfterMillis());
                }
                Instant now = clock.instant();
                List<Claimed> rows = jdbc.query("UPDATE command_outbox SET state = 'IN_FLIGHT', attempt_count = attempt_count + 1,"
                                + " next_attempt_at = NULL, lease_owner = ?, lease_expires_at = now() + make_interval(secs => ?), updated_at = ?"
                                + " WHERE command_id = (SELECT o.command_id FROM command_outbox o WHERE o.command_id = ?"
                                + " AND o.state IN ('QUEUED', 'RETRY_WAIT') AND o.next_attempt_at <= now() AND now() < o.expires_at AND " + NOT_ACKNOWLEDGED + " AND " + DEVICE_GUARDS
                                + " FOR UPDATE SKIP LOCKED)"
                                + " RETURNING device_id, topic, payload_text, payload_sha256, payload_epoch_seconds, attempt_count, max_attempts, correlation_id",
                        (rs, i) -> new Claimed(commandId, rs.getObject("device_id", UUID.class), rs.getString("topic"),
                                new OutboxPayload(rs.getString("payload_text"), rs.getString("payload_sha256"), rs.getLong("payload_epoch_seconds")),
                                rs.getInt("attempt_count"), rs.getInt("max_attempts"), leaseOwner, rs.getString("correlation_id")),
                        leaseOwner, lease.toMillis() / 1000.0, Timestamp.from(now), commandId);
                if (rows.isEmpty()) return ClaimResult.none();
                Claimed claimed = rows.get(0);
                // a QUEUED row has attempt_count 0 (V37), so attempt 1 is the first claim and any later claim leaves RETRY_WAIT
                history.record(new OutboxTransitionRecord(commandId, claimed.attempt() == 1 ? OutboxState.QUEUED : OutboxState.RETRY_WAIT,
                        OutboxState.IN_FLIGHT, claimed.attempt(), claimed.attempt() == 1 ? "CLAIMED" : "RETRY_CLAIMED", claimed.correlationId(), now));
                return new ClaimResult(Optional.of(claimed), false, 0);
            });
        } catch (DataIntegrityViolationException e) {
            if (isUniqueViolation(e)) return ClaimResult.none();
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
            List<Long> remaining = jdbc.query("SELECT (EXTRACT(EPOCH FROM (expires_at - clock_timestamp())) * 1000)::bigint FROM command_outbox"
                            + " WHERE command_id = ? AND state = 'IN_FLIGHT' AND lease_owner = ? AND attempt_count = ? FOR UPDATE",
                    (rs, i) -> rs.getLong(1), claimed.commandId(), claimed.leaseOwner(), claimed.attempt());
            if (remaining.isEmpty())
                return new Resolution(Disposition.FENCED, claimed.commandId(), claimed.deviceId(), null, null, null, at, false, claimed.correlationId());
            Duration remainingWindow = Duration.ofMillis(remaining.get(0));
            boolean inWindow = remainingWindow.isPositive();
            if (result == AttemptResult.CONFIRMED && inWindow) return publish(claimed, at);
            if (result == AttemptResult.CONFIRMED) {
                // Completed after expires_at: recorded as late and uncertain, never as a normal publication (R31.4).
                return deadLetter(claimed.commandId(), claimed.deviceId(), claimed.attempt(), claimed.correlationId(), at,
                        OutboxTerminalReason.UNCERTAIN_EXPIRED, "PUBLISH_COMPLETED_AFTER_EXPIRY", true,
                        "DELIVERY_UNCONFIRMED: the publish completed after the delivery window had expired; the device may reject the command as stale"
                                + " and NEXUS does not resend it");
            }
            return switch (result) {
                case NOT_SENT -> notSent(claimed, at, code, remainingWindow);
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

    /**
     * A PROVEN non-publication (the client refused the hand-over, contract R10). The retry policy decides, from durable facts only (the
     * attempt number, the stored budget and the window left on the database clock): park the command in RETRY_WAIT with a backoff, or
     * dead-letter it as RETRY_EXHAUSTED or EXPIRED. Either way the same payload, command id, hash and timestamp stay in the row and
     * nothing is resent from here. ActionHistory stays ACCEPTED_FOR_DISPATCH while a retry is pending: the command is neither
     * published nor failed yet.
     */
    private Resolution notSent(Claimed c, Instant at, String code, Duration remainingWindow) {
        String errorCode = "NOT_SENT_" + safe(code);
        OutboxRetryPolicy.Decision decision = retryPolicy.decide(AttemptResult.NOT_SENT, c.attempt(),
                c.maxAttempts(), remainingWindow, errorCode);
        if (decision.retry()) {
            jdbc.update("UPDATE command_outbox SET state = 'RETRY_WAIT', lease_owner = NULL, lease_expires_at = NULL,"
                            + " next_attempt_at = clock_timestamp() + make_interval(secs => ?), last_error_code = ?, updated_at = ? WHERE command_id = ?",
                    decision.delay().toMillis() / 1000.0, code64(errorCode), Timestamp.from(at), c.commandId());
            String message = "Publication attempt " + c.attempt() + " of " + c.maxAttempts() + " was not sent (" + safe(code)
                    + "); the same command is retried in " + decision.delay().toMillis() + " ms";
            jdbc.update("UPDATE action_history SET message = ? WHERE request_id = ? AND status = 'ACCEPTED_FOR_DISPATCH'", message, c.commandId());
            history.append(c.commandId(), Fact.ATTEMPT_FAILED_TRANSIENT, OutboxState.IN_FLIGHT.name(), OutboxState.RETRY_WAIT.name(), c.attempt(),
                    code64(errorCode), Source.DISPATCHER, c.correlationId(), at, "retry in " + decision.delay().toMillis() + " ms");
            return new Resolution(Disposition.RETRY_SCHEDULED, c.commandId(), c.deviceId(), null, errorCode, message, at, false, c.correlationId());
        }
        if (decision.reason() == OutboxTerminalReason.RETRY_EXHAUSTED)
            return deadLetter(c.commandId(), c.deviceId(), c.attempt(), c.correlationId(), at, OutboxTerminalReason.RETRY_EXHAUSTED, errorCode, false,
                    "MQTT publish failed: the command was not sent in any of " + c.attempt() + " attempts (" + safe(code) + ")");
        return deadLetter(c.commandId(), c.deviceId(), c.attempt(), c.correlationId(), at, OutboxTerminalReason.EXPIRED, errorCode, false,
                "Command expired before delivery (EXPIRED): the delivery window left no time for another attempt after attempt " + c.attempt()
                        + " was not sent (" + safe(code) + "); the command was never sent");
    }

    private Resolution publish(Claimed c, Instant appClockAt) {
        // The publication instant, and with it the persisted ACK deadline, come from the DATABASE clock (contract R14, ticket P4.4): the
        // timeout scanner and the ACK handler judge against the same clock, so a skewed application clock can neither make a command time
        // out before the device had its time nor hide a real timeout. The application-clock instant stays on the Resolution only.
        Instant at = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        jdbc.update("UPDATE command_outbox SET state = 'PUBLISHED', lease_owner = NULL, lease_expires_at = NULL,"
                        + " first_publish_confirmed_at = ?, terminal_at = ?, last_error_code = NULL, updated_at = ? WHERE command_id = ?",
                Timestamp.from(at), Timestamp.from(at), Timestamp.from(at), c.commandId());
        int updated = jdbc.update("UPDATE action_history SET status = 'SUCCESS', \"timestamp\" = ?, message = ?, published_at = ?, ack_deadline_at = ?"
                        + " WHERE request_id = ? AND status = 'ACCEPTED_FOR_DISPATCH'",
                Timestamp.from(at), MSG_PUBLISHED, Timestamp.from(at), Timestamp.from(at.plus(ackTimeout)), c.commandId());
        history.record(new OutboxTransitionRecord(c.commandId(), OutboxState.IN_FLIGHT, OutboxState.PUBLISHED, c.attempt(),
                "BROKER_CONFIRMED", c.correlationId(), at));
        return new Resolution(Disposition.PUBLISHED, c.commandId(), c.deviceId(), null, null, MSG_PUBLISHED, appClockAt, updated == 1, c.correlationId(), ackRecorded(c.commandId()));
    }

    /**
     * IN_FLIGHT (or QUEUED/RETRY_WAIT for an expiry) to DEAD_LETTER. The caller already holds the outbox row lock in this
     * transaction. {@code uncertain} additionally appends ATTEMPT_UNCERTAIN so the history never calls an uncertain
     * attempt a plain failure.
     */
    private Resolution deadLetter(UUID commandId, UUID deviceId, int attempt, String correlationId, Instant at,
                                  OutboxTerminalReason reason, String errorCode, boolean uncertain, String message) {
        // An UNCERTAIN outcome may have put the command on the broker, so the publish gate counts it (CommandPublishGate) against the database clock.
        // Its terminal instant is therefore the DATABASE clock as well (P4.6 correction): with the application clock a skewed host could record an
        // instant the gate no longer counts and let a second publication start inside the same second. The history facts and the message keep
        // the application instant as before; only terminal_at of a possibly-published attempt changes, and the other outcomes are untouched.
        jdbc.update("UPDATE command_outbox SET state = 'DEAD_LETTER', next_attempt_at = NULL, lease_owner = NULL, lease_expires_at = NULL,"
                        + " terminal_at = " + (uncertain ? "clock_timestamp()" : "?") + ", terminal_reason = ?, last_error_code = ?, updated_at = ? WHERE command_id = ?",
                uncertain ? new Object[]{reason.name(), code64(errorCode), Timestamp.from(at), commandId}
                          : new Object[]{Timestamp.from(at), reason.name(), code64(errorCode), Timestamp.from(at), commandId});
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

    // ------------------------------------------------------------------------------ crash-safe reactions (P4.3)

    /**
     * Dead letters whose follow-up signals may have been lost to a crash between the outcome commit and the post-commit reaction
     * (contract R12: the Twin verification told once, one COMMAND_PUBLICATION_FAILED rule fact). Only outcomes that PROVE the command
     * was never sent qualify (never UNCERTAIN_EXPIRED, never one the device acknowledged), and only when the outcome is older than a
     * few seconds so the normal reaction has finished. A row is reported while its Twin verification is still PENDING for that command
     * (no age limit; telling the Twin clears it) or while its rule fact is missing within a ten-minute lookback. Both signals are
     * idempotent (the rule fact is unique per command, commandFailed only changes PENDING), so re-applying them is harmless.
     */
    public List<Unreacted> unreactedFailures(int limit) {
        return jdbc.query("SELECT o.command_id, o.device_id, o.terminal_reason, o.last_error_code, o.terminal_at, o.correlation_id, a.message,"
                        + " EXISTS (SELECT 1 FROM current_twin_verification_state v WHERE v.device_id = o.device_id"
                        + " AND v.desired_command_id = o.command_id AND v.verification_status = 'PENDING') AS twin_pending"
                        + " FROM command_outbox o JOIN action_history a ON a.request_id = o.command_id"
                        + " WHERE o.state = 'DEAD_LETTER' AND o.terminal_reason IN ('PERMANENT_FAILURE', 'EXPIRED', 'PAYLOAD_INTEGRITY', 'RETRY_EXHAUSTED')"
                        + " AND a.status = 'FAILURE' AND a.ack_status IS NULL AND o.terminal_at < now() - INTERVAL '5 seconds'"
                        + " AND (EXISTS (SELECT 1 FROM current_twin_verification_state v WHERE v.device_id = o.device_id"
                        + " AND v.desired_command_id = o.command_id AND v.verification_status = 'PENDING')"
                        + " OR (o.terminal_at > now() - INTERVAL '10 minutes' AND NOT EXISTS (SELECT 1 FROM rule_trigger_facts f"
                        + " WHERE f.trigger_type = 'COMMAND_PUBLICATION_FAILED' AND f.source_key = o.command_id::text)))"
                        + " ORDER BY o.terminal_at LIMIT ?",
                (rs, i) -> new Unreacted(new Resolution(Disposition.DEAD_LETTERED, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        OutboxTerminalReason.valueOf(rs.getString(3)), rs.getString(4), rs.getString(7), rs.getTimestamp(5).toInstant(), true,
                        rs.getString(6)), rs.getBoolean(8)), limit);
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
     * A QUEUED or RETRY_WAIT command whose device has ALREADY acknowledged it (the first ACK is authoritative, R13). It must not be published,
     * and it has no confirmed publication either, so it ends as DEAD_LETTER UNCERTAIN_EXPIRED with the honest message DELIVERY_UNCONFIRMED:
     * the delivery record never claims a broker confirmation it did not get, the failure signals stay suppressed (an acknowledged command is
     * never reported as a publication failure), and the per-device hold of R32 applies. Nothing is sent; ActionHistory keeps the ACK.
     */
    public List<Resolution> resolveAcknowledgedUnpublished(int limit) {
        return tx.execute(status -> {
            Instant at = clock.instant();
            List<Object[]> rows = jdbc.query("UPDATE command_outbox o SET state = 'DEAD_LETTER', next_attempt_at = NULL, terminal_at = ?, terminal_reason = 'UNCERTAIN_EXPIRED',"
                            + " last_error_code = 'ACKNOWLEDGED_BEFORE_PUBLICATION', updated_at = ?"
                            + " WHERE o.command_id IN (SELECT x.command_id FROM command_outbox x JOIN action_history ak ON ak.request_id = x.command_id"
                            + " WHERE x.state IN ('QUEUED', 'RETRY_WAIT') AND ak.ack_status IS NOT NULL ORDER BY x.created_at LIMIT ? FOR UPDATE OF x SKIP LOCKED)"
                            + " RETURNING o.command_id, o.device_id, o.attempt_count, o.correlation_id",
                    (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4)},
                    Timestamp.from(at), Timestamp.from(at), limit);
            List<Resolution> out = new ArrayList<>();
            for (Object[] r : rows)
                out.add(finishDeadLetter((UUID) r[0], (UUID) r[1], (Integer) r[2], (String) r[3], at, OutboxTerminalReason.UNCERTAIN_EXPIRED,
                        "ACKNOWLEDGED_BEFORE_PUBLICATION", true,
                        "DELIVERY_UNCONFIRMED: the device acknowledged the command before NEXUS recorded a publication; it is not published (again)"
                                + " and no broker confirmation is claimed",
                        (Integer) r[2] > 0 ? OutboxState.RETRY_WAIT : OutboxState.QUEUED));
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
                            + " terminal_at = clock_timestamp(), terminal_reason = 'UNCERTAIN_EXPIRED', last_error_code = 'LEASE_EXPIRED_UNCERTAIN', updated_at = ?"
                            + " WHERE command_id IN (SELECT command_id FROM command_outbox WHERE state = 'IN_FLIGHT' AND lease_expires_at < now()"
                            + " ORDER BY lease_expires_at LIMIT ? FOR UPDATE SKIP LOCKED)"
                            + " RETURNING command_id, device_id, attempt_count, correlation_id",
                    (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4)},
                    Timestamp.from(at), limit);
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
