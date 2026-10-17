package com.nexus.action.outbox;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The publish-rate limiter shared by every dispatcher of every backend instance (contract R18, ledger P4-090). It is consulted inside the
 * claim transaction (T2) of EVERY claim (first attempts, retries and the draining of a rolled-back outbox alike), so a command that is not
 * allowed to be published is simply not claimed: it stays QUEUED or RETRY_WAIT with its attempt counter, payload, timestamp and delivery
 * window untouched, and the existing expiry sweep dead-letters it visibly if its window ends while it waits.
 *
 * <p><b>Algorithm: a sliding-window permit count on database facts.</b> At most {@code rate} publications may be IN FLIGHT or CONFIRMED
 * within any one second. At claim time the gate counts, on the database clock, the commands that are IN_FLIGHT plus those whose publish was
 * confirmed (state PUBLISHED, {@code terminal_at} is the database-clock confirmation instant) or ended uncertain (UNCERTAIN_EXPIRED: the
 * broker may have received them) during the last second, and the claim is allowed only while that count is below the rate. A command is
 * therefore counted continuously from its claim until one second after its outcome, so the confirmations of any one-second interval can
 * never exceed the rate (proof: take the latest claim among N confirmations inside one second; every other one is either confirmed within
 * a second before that claim or still in flight at it, so N - 1 is below the rate). The burst capacity equals the rate, which is the
 * bucket capacity of the token bucket the contract names; unlike an in-memory bucket the state lives in the database, so it is shared by
 * all instances, survives a restart and cannot be reset by one.
 *
 * <p><b>Coordination.</b> The count and the claim that follows are one transaction under a transaction-scoped advisory lock (key space 4601,
 * object 2), so two dispatcher instances cannot both observe a free permit and both take it. The lock is the FIRST lock of the claim
 * transaction (before any outbox row lock) and nothing that holds an outbox row, the history lock or the device row ever waits for it; the
 * claim transaction does no network I/O. If the count cannot be made (database error) the exception aborts the claim: fail closed, nothing is
 * published without permission.
 *
 * <p>Limits stated honestly: the gate bounds permits (claims), which lead to at most one publication each; broker confirmation times can
 * still shift by the publisher's latency. The UNCERTAIN term uses the instant recorded for that outcome, which is the application clock
 * (it is rare, and a skewed clock only moves it in or out of the window).
 */
@Component
public class CommandPublishGate {
    static final int LOCK_CLASS = 4601;
    static final int GATE_LOCK = 2;
    /** The window the rate is measured over; the property is a rate per second. */
    static final double WINDOW_SECONDS = 1.0;

    /** Result of one permit decision. {@code retryAfterMillis} is when the oldest counted publication leaves the window (0 if free). */
    public record Decision(boolean allowed, int used, long retryAfterMillis) {}

    private final JdbcTemplate jdbc;
    private final CommandFlowSettings settings;

    @Autowired
    public CommandPublishGate(JdbcTemplate jdbc, CommandFlowSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /**
     * Takes the gate lock and decides whether one more claim may proceed. Must be called inside the claim transaction and the claim must be
     * made in that same transaction (the lock is released when it ends).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Decision permit() {
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> { }, LOCK_CLASS, GATE_LOCK);
        int rate = settings.publishRatePerSecond();
        Object[] row = jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM command_outbox WHERE state = 'IN_FLIGHT') + w.n,"
                        + " COALESCE(CEIL(EXTRACT(EPOCH FROM (w.oldest + make_interval(secs => ?) - clock_timestamp())) * 1000), 0)::bigint"
                        + " FROM (SELECT count(*) AS n, min(terminal_at) AS oldest FROM command_outbox"
                        + "   WHERE state IN ('PUBLISHED', 'DEAD_LETTER') AND terminal_at > clock_timestamp() - make_interval(secs => ?)"
                        + "   AND (state = 'PUBLISHED' OR terminal_reason = 'UNCERTAIN_EXPIRED')) w",
                (rs, i) -> new Object[]{rs.getInt(1), rs.getLong(2)}, WINDOW_SECONDS, WINDOW_SECONDS);
        int used = (Integer) row[0];
        if (used < rate) return new Decision(true, used, 0);
        return new Decision(false, used, Math.max(1L, (Long) row[1]));
    }
}
