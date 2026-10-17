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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The runtime counterpart of the V39 backfill (contract R16, decision D3): a legacy (flag OFF) command can be stranded in
 * ACCEPTED_FOR_DISPATCH by a crash between the commit and the publish. Such a command is classified, once, with a DELIVERY_UNKNOWN
 * history fact; that is ALL this class ever does.
 *
 * <ul>
 * <li><b>Never publishes</b>, never creates an outbox row, never changes ActionHistory, a desired state or a verification, never
 *     fabricates a publication or an ACK, never refreshes a payload or a timestamp. It has no dependency on the MQTT client, the
 *     command executor or the dispatcher (proven by {@code OutboxArchitectureTest}).</li>
 * <li><b>Conservative eligibility</b> (the V39 rules plus the same grace): SEND_COMMAND, still ACCEPTED_FOR_DISPATCH, no recorded
 *     publication, no device ACK, NO outbox row (so a flag-ON obligation is never touched), no history fact at all (so a row that
 *     already has a DELIVERY_UNKNOWN or any other fact is never classified twice), and older than the grace, which exceeds any
 *     legitimate legacy dispatch (its blocking publish is bounded by about 15 seconds). The age is judged by the DATABASE clock.</li>
 * <li><b>Idempotent and race-safe</b>: each candidate is re-checked under the action_history row lock, the lock the legacy publish, the
 *     ACK handler and another reconciler also need, so a command that completed, was acknowledged or was already classified meanwhile is
 *     skipped; the history writer takes its advisory lock last.</li>
 * <li>Bounded batches and a fixed delay; an interrupted run is simply repeated. It does not consult the outbox flag.</li>
 * </ul>
 * A device that did receive the command may still acknowledge it: that ACK is recorded as a fact (R13) and implies nothing about NEXUS
 * having resent it.
 */
@Component
public class LegacyDeliveryReconciler {
    private static final Logger log = LoggerFactory.getLogger(LegacyDeliveryReconciler.class);
    static final int BATCH = 100;
    /** Same as the V39 backfill. */
    public static final Duration MIN_GRACE = Duration.ofMinutes(5);

    private static final String ELIGIBLE = "a.action_type = 'SEND_COMMAND' AND a.status = 'ACCEPTED_FOR_DISPATCH'"
            + " AND a.published_at IS NULL AND a.ack_status IS NULL"
            + " AND a.\"timestamp\" < clock_timestamp() - make_interval(secs => ?)"
            + " AND NOT EXISTS (SELECT 1 FROM command_outbox o WHERE o.command_id = a.request_id)"
            + " AND NOT EXISTS (SELECT 1 FROM command_state_history h WHERE h.command_id = a.request_id)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CommandStateHistory history;
    private final MeterRegistry meters;
    private final Duration grace;
    private final boolean enabled;

    @Autowired
    public LegacyDeliveryReconciler(JdbcTemplate jdbc, PlatformTransactionManager transactions, CommandStateHistory history,
                                    ObjectProvider<MeterRegistry> meters,
                                    @Value("${nexus.command.legacy-reconcile-grace:PT5M}") Duration grace,
                                    @Value("${nexus.command.lifecycle-scanners-enabled:true}") boolean enabled) {
        if (grace == null || grace.compareTo(MIN_GRACE) < 0)
            throw new IllegalStateException("nexus.command.legacy-reconcile-grace must be at least " + MIN_GRACE
                    + ": it has to exceed any legitimate legacy dispatch (R16)");
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.history = history;
        this.meters = meters.getIfAvailable();
        this.grace = grace;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${nexus.command.legacy-reconcile-interval:PT60S}")
    public void scheduled() {
        if (!enabled) return;
        try {
            reconcile(BATCH);
        } catch (RuntimeException failure) {
            log.warn("Legacy delivery reconciliation failed; it is repeated on the next pass: {}", failure.getMessage());
            if (meters != null) meters.counter("nexus.command.legacy.reconcile", "outcome", "error").increment();
        }
    }

    public boolean enabled() { return enabled; }

    /** @return the number of commands newly classified DELIVERY_UNKNOWN by this call */
    public int reconcile(int limit) {
        List<UUID> stranded = jdbc.queryForList("SELECT a.request_id FROM action_history a WHERE " + ELIGIBLE
                + " ORDER BY a.\"timestamp\" LIMIT ?", UUID.class, graceSeconds(), limit);
        int marked = 0;
        for (UUID commandId : stranded) {
            Boolean written = tx.execute(status -> mark(commandId));
            if (Boolean.TRUE.equals(written)) marked++;
        }
        if (marked > 0 && meters != null) meters.counter("nexus.command.legacy.reconcile", "outcome", "marked").increment(marked);
        return marked;
    }

    private double graceSeconds() { return grace.toMillis() / 1000.0; }

    private boolean mark(UUID commandId) {
        // 1. Take the action_history row lock (the lock the legacy publish, an ACK and another reconciler also need) and read the row's own
        //    columns. 2. Only THEN evaluate the conditions that live in other tables, in separate statements: a single statement would
        //    keep using the snapshot it started with, so a reconciler that waited for the lock could not see the fact its predecessor just
        //    committed (a locking read re-checks only the locked row itself) and would classify the command a second time.
        var rows = jdbc.queryForList("SELECT action_type, status, published_at, ack_status, clock_timestamp() AS db_now,"
                + " (\"timestamp\" < clock_timestamp() - make_interval(secs => ?)) AS old_enough FROM action_history WHERE request_id = ? FOR UPDATE",
                graceSeconds(), commandId);
        if (rows.isEmpty()) return false;
        var r = rows.get(0);
        if (!"SEND_COMMAND".equals(String.valueOf(r.get("action_type"))) || !"ACCEPTED_FOR_DISPATCH".equals(String.valueOf(r.get("status")))
                || r.get("published_at") != null || r.get("ack_status") != null || !Boolean.TRUE.equals(r.get("old_enough"))) return false;
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM command_outbox WHERE command_id = ?)", Boolean.class, commandId))) return false;
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM command_state_history WHERE command_id = ?)", Boolean.class, commandId))) return false;
        history.append(commandId, Fact.DELIVERY_UNKNOWN, null, "DELIVERY_UNKNOWN", 0, "LEGACY_DELIVERY_UNRECORDED", Source.RECONCILER,
                commandId.toString(), ((java.sql.Timestamp) r.get("db_now")).toInstant(),
                "Accepted before the command outbox existed or while it was OFF; delivery was never recorded and is never retried automatically");
        return true;
    }
}
