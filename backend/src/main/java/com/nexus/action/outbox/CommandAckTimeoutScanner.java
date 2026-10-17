package com.nexus.action.outbox;

import com.nexus.action.outbox.CommandStateHistory.Fact;
import com.nexus.action.outbox.CommandStateHistory.Source;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The single owner of the persisted ACK_TIMED_OUT delivery-lifecycle fact (contract R14, ticket P4.4).
 *
 * <ul>
 * <li>The deadline is the one stored on ActionHistory ({@code ack_deadline_at}, first confirmed publication plus the ACK timeout, written
 *     by the dispatcher from the DATABASE clock). It is read, never moved. A command that was only queued or is still waiting for a retry
 *     has no deadline and is never timed out.</li>
 * <li>Eligibility and the timeout instant are judged by the DATABASE clock ({@code clock_timestamp()}), strictly after the deadline
 *     (equality is not a timeout), so an application clock that is wrong in either direction cannot time a command out early or hide a
 *     timeout. The fact is stamped with the database clock too.</li>
 * <li>One timeout fact per command: each candidate is re-read under the action_history row lock (the same lock a first ACK takes), so
 *     two scanners, or a scanner and an ACK, serialise on that row; the loser sees the winner's result and writes nothing.</li>
 * <li>A timeout is a statement about the ACKNOWLEDGEMENT only. It never publishes or requeues anything, never touches the outbox row, a
 *     desired state or a Twin verification (decision A9), and never claims the device did not act. The rule trigger fact for the same
 *     event stays with {@code RuleAckTimeoutScanner}; this class emits none.</li>
 * <li>Bounded batches, no busy loop (fixed delay), a failure is logged and repeated on the next pass; it is always a bean and does not
 *     consult the outbox flag, because legacy (flag OFF) commands carry deadlines as well.</li>
 * </ul>
 */
@Component
public class CommandAckTimeoutScanner {
    private static final Logger log = LoggerFactory.getLogger(CommandAckTimeoutScanner.class);
    static final int BATCH = 100;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CommandStateHistory history;
    private final MeterRegistry meters;
    private final boolean enabled;

    @Autowired
    public CommandAckTimeoutScanner(JdbcTemplate jdbc, PlatformTransactionManager transactions, CommandStateHistory history,
                                    ObjectProvider<MeterRegistry> meters,
                                    @Value("${nexus.command.lifecycle-scanners-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.history = history;
        this.meters = meters.getIfAvailable();
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${nexus.command.ack-timeout-scan-interval:PT5S}")
    public void scheduled() {
        if (!enabled) return;
        try {
            scan(BATCH);
        } catch (RuntimeException failure) {
            log.warn("Persisted ACK timeout scan failed; it is repeated on the next pass: {}", failure.getMessage());
            if (meters != null) meters.counter("nexus.command.ack.timeout.scan", "outcome", "error").increment();
        }
    }

    public boolean enabled() { return enabled; }

    /** @return the number of ACK_TIMED_OUT facts newly recorded by this call */
    public int scan(int limit) {
        List<UUID> due = jdbc.queryForList("SELECT a.request_id FROM action_history a"
                + " WHERE a.action_type = 'SEND_COMMAND' AND a.status = 'SUCCESS' AND a.ack_status IS NULL"
                + " AND a.ack_deadline_at IS NOT NULL AND a.ack_deadline_at < clock_timestamp()"
                + " AND NOT EXISTS (SELECT 1 FROM command_state_history h WHERE h.command_id = a.request_id AND h.fact_type = 'ACK_TIMED_OUT')"
                + " ORDER BY a.ack_deadline_at LIMIT ?", UUID.class, limit);
        int recorded = 0;
        for (UUID commandId : due) {
            Boolean written = tx.execute(status -> record(commandId));
            if (Boolean.TRUE.equals(written)) recorded++;
        }
        if (recorded > 0 && meters != null) meters.counter("nexus.command.ack.timeout.scan", "outcome", "recorded").increment(recorded);
        return recorded;
    }

    private boolean record(UUID commandId) {
        // The action_history row lock is the serialisation point shared with the ACK handler and with another scanner.
        var rows = jdbc.queryForList("SELECT ack_status, ack_deadline_at, status, (ack_deadline_at < clock_timestamp()) AS overdue, clock_timestamp() AS db_now,"
                + " COALESCE((SELECT o.attempt_count FROM command_outbox o WHERE o.command_id = request_id), 0) AS attempt"
                + " FROM action_history WHERE request_id = ? FOR UPDATE", commandId);
        if (rows.isEmpty()) return false;
        var r = rows.get(0);
        if (r.get("ack_status") != null) return false;                               // a first ACK won the race: nothing to time out
        if (!"SUCCESS".equals(String.valueOf(r.get("status"))) || r.get("ack_deadline_at") == null) return false;
        if (!Boolean.TRUE.equals(r.get("overdue"))) return false;                    // equality or a moved deadline is not a timeout
        Boolean already = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM command_state_history WHERE command_id = ? AND fact_type = 'ACK_TIMED_OUT')",
                Boolean.class, commandId);
        if (Boolean.TRUE.equals(already)) return false;                              // another scanner already recorded it
        Instant deadline = ((Timestamp) r.get("ack_deadline_at")).toInstant();
        Instant dbNow = ((Timestamp) r.get("db_now")).toInstant();
        history.append(commandId, Fact.ACK_TIMED_OUT, null, null, ((Number) r.get("attempt")).intValue(), "ACK_DEADLINE_PASSED", Source.TIMEOUT_SCANNER,
                commandId.toString(), dbNow, "no device ACK was recorded by " + deadline + "; this says nothing about whether the device acted");
        return true;
    }
}
