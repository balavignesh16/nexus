package com.nexus.infrastructure.retention;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Daily retention. Each category runs in its OWN transaction (REQUIRES_NEW), so one blocked or failing category can never roll
 * back the others (a single shared transaction used to do exactly that: one restricted action_history row silently rolled back
 * every category). Failures are logged at ERROR with their cause, counted, collected in a {@link Report} and, for the scheduled
 * entry point, surfaced by throwing {@link RetentionFailedException} after every category has been attempted.
 *
 * Transaction boundaries and failure behaviour, per category:
 * <ul>
 * <li>TELEMETRY, PIPELINE_TRACES: drop_chunks on the hypertable, independent.</li>
 * <li>DOMAIN_EVENTS: delete old events, independent.</li>
 * <li>COMMAND_OUTBOX: purge RESOLVED (PUBLISHED / DEAD_LETTER) delivery rows older than the action retention. Unresolved rows are
 *     never selected and the database refuses to delete them.</li>
 * <li>ACTION_HISTORY: delete old rows EXCEPT rows still referenced by current desired Twin state (never cleared here) or by any
 *     command_outbox row. A failure here leaves everything else already committed.</li>
 * </ul>
 */
@Component
public class RetentionManager {
    private static final Logger log = LoggerFactory.getLogger(RetentionManager.class);

    public static final String TELEMETRY = "TELEMETRY";
    public static final String PIPELINE_TRACES = "PIPELINE_TRACES";
    public static final String DOMAIN_EVENTS = "DOMAIN_EVENTS";
    public static final String COMMAND_OUTBOX = "COMMAND_OUTBOX";
    public static final String ACTION_HISTORY = "ACTION_HISTORY";
    public static final String INBOUND_MESSAGE_DEDUPE = "INBOUND_MESSAGE_DEDUPE";

    /**
     * Floor for nexus.retention.inbound-dedupe-hours (P4.5, contract R17/R19). The dedupe entry is the only protection against a redelivered
     * telemetry message being stored twice, so it must outlive a realistic backend outage; the default is 168 hours and a value below this
     * floor is refused at startup instead of silently weakening the protection. There is no finite value that covers every redelivery
     * (the broker keeps a persistent session's unacknowledged messages until the client returns): protection ends when the entry is purged.
     */
    public static final int MIN_INBOUND_DEDUPE_HOURS = 24;

    /** Outcome of one category: rows affected (or -1 when the operation does not report a count) and the failure, if any. */
    public record CategoryResult(String category, boolean success, long rows, String error) {}

    public record Report(List<CategoryResult> results) {
        public boolean failed() { return results.stream().anyMatch(r -> !r.success()); }
        public List<String> failedCategories() { return results.stream().filter(r -> !r.success()).map(CategoryResult::category).toList(); }
        public CategoryResult of(String category) { return results.stream().filter(r -> r.category().equals(category)).findFirst().orElseThrow(); }
    }

    public static class RetentionFailedException extends RuntimeException {
        private final transient Report report;
        public RetentionFailedException(Report report) {
            super("Retention categories failed: " + report.failedCategories());
            this.report = report;
        }
        public Report getReport() { return report; }
    }

    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    private MeterRegistry meters;

    @Value("${nexus.retention.telemetry-days:30}")
    private int telemetryDays;

    @Value("${nexus.retention.pipeline-days:7}")
    private int pipelineDays;

    @Value("${nexus.retention.events-days:90}")
    private int eventsDays;

    @Value("${nexus.retention.actions-days:90}")
    private int actionsDays;

    @Value("${nexus.retention.inbound-dedupe-hours:168}")
    private int inboundDedupeHours = 168;

    /** Rows deleted per transaction and the most batches one run may issue, so a purge never holds a long lock or runs unbounded. */
    @Value("${nexus.retention.inbound-dedupe-batch-size:1000}")
    private int inboundDedupeBatchSize = 1000;

    @Value("${nexus.retention.inbound-dedupe-max-batches:100}")
    private int inboundDedupeMaxBatches = 100;

    @jakarta.annotation.PostConstruct
    void validateInboundDedupeSettings() {
        if (inboundDedupeHours < MIN_INBOUND_DEDUPE_HOURS)
            throw new IllegalStateException("nexus.retention.inbound-dedupe-hours must be at least " + MIN_INBOUND_DEDUPE_HOURS
                    + " (was " + inboundDedupeHours + "): a shorter window would stop recognising redelivered telemetry");
        if (inboundDedupeBatchSize < 1 || inboundDedupeMaxBatches < 1)
            throw new IllegalStateException("nexus.retention.inbound-dedupe-batch-size and -max-batches must be positive");
    }

    public RetentionManager(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Autowired(required = false)
    void setMeters(MeterRegistry meters) {
        this.meters = meters;
    }

    @Scheduled(cron = "0 0 2 * * *") // Run at 2 AM every day
    public void cleanupOldData() {
        Report report = runCleanup();
        if (report.failed()) throw new RetentionFailedException(report);
    }

    /** Runs every category, each in its own transaction, and reports the outcome of each. Never throws for a category failure. */
    public Report runCleanup() {
        log.info("Starting retention cleanup job...");
        List<CategoryResult> results = new ArrayList<>();
        // Native TimescaleDB drop_chunks for hypertables.
        results.add(run(TELEMETRY, () -> {
            entityManager.createNativeQuery("SELECT drop_chunks('telemetry_history', INTERVAL '" + telemetryDays + " days')").getResultList();
            return -1L;
        }));
        results.add(run(PIPELINE_TRACES, () -> {
            entityManager.createNativeQuery("SELECT drop_chunks('pipeline_traces', INTERVAL '" + pipelineDays + " days')").getResultList();
            return -1L;
        }));
        results.add(run(DOMAIN_EVENTS, () ->
                (long) entityManager.createNativeQuery("DELETE FROM domain_events WHERE timestamp < NOW() - INTERVAL '" + eventsDays + " days'").executeUpdate()));
        // Phase 4 (V37/V38): a delivery obligation is never destroyed by retention. Resolved rows go first; unresolved rows are
        // protected by the database, and action_history rows that still have an outbox row are never selected.
        results.add(run(COMMAND_OUTBOX, () ->
                (long) entityManager.createNativeQuery("DELETE FROM command_outbox WHERE state IN ('PUBLISHED', 'DEAD_LETTER') AND terminal_at < NOW() - INTERVAL '" + actionsDays + " days'").executeUpdate()));
        // Rows referenced by current desired Twin state stay (the reference is NOT cleared: desired state is never cleared
        // automatically), and so do rows that still have an outbox row. Everything else that is old is removed.
        results.add(run(ACTION_HISTORY, () ->
                (long) entityManager.createNativeQuery("DELETE FROM action_history a WHERE a.timestamp < NOW() - INTERVAL '" + actionsDays + " days'"
                        + " AND NOT EXISTS (SELECT 1 FROM command_outbox o WHERE o.command_id = a.request_id)"
                        + " AND NOT EXISTS (SELECT 1 FROM current_desired_twin_state d WHERE d.command_id = a.request_id)").executeUpdate()));
        // P4.5: replay-protection entries of the telemetry inbox, purged by database age in small independent transactions.
        results.add(report(INBOUND_MESSAGE_DEDUPE, this::purgeInboundMessageDedupe));
        Report report = new Report(List.copyOf(results));
        if (report.failed()) log.error("Retention cleanup job completed WITH FAILURES in categories {}; the other categories were committed.", report.failedCategories());
        else log.info("Retention cleanup job completed.");
        return report;
    }

    /**
     * Deletes dedupe entries older than the window, one bounded batch per REQUIRES_NEW transaction, until a batch comes back short or the
     * per-run limit is reached (the next run continues). Age is measured by the database clock (NOW() of each batch transaction), never
     * the application clock. A purged id is accepted again: the documented limit of the protection.
     */
    private Long purgeInboundMessageDedupe() {
        long total = 0;
        for (int batch = 0; batch < inboundDedupeMaxBatches; batch++) {
            Integer deleted = transaction.execute(status -> entityManager.createNativeQuery(
                    "DELETE FROM inbound_message_dedupe WHERE ctid IN (SELECT ctid FROM inbound_message_dedupe"
                            + " WHERE received_at < NOW() - make_interval(hours => " + inboundDedupeHours + ")"
                            + " ORDER BY received_at LIMIT " + inboundDedupeBatchSize + ")").executeUpdate());
            int rows = deleted == null ? 0 : deleted;
            total += rows;
            if (rows < inboundDedupeBatchSize) break;
        }
        return total;
    }

    private CategoryResult run(String category, Supplier<Long> body) {
        return report(category, () -> transaction.execute(status -> body.get()));
    }

    /** Runs one category and reports it; the body supplies its own transaction(s). A failure is contained to the category. */
    private CategoryResult report(String category, Supplier<Long> body) {
        try {
            Long rows = body.get();
            long count = rows == null ? -1L : rows;
            log.info("Retention category {} completed ({} rows affected)", category, count < 0 ? "n/a" : count);
            if (meters != null && count > 0) meters.counter("nexus.retention.rows.deleted", "category", category).increment(count);
            return new CategoryResult(category, true, count, null);
        } catch (Exception e) {
            log.error("Retention category {} FAILED and was rolled back on its own; other categories are unaffected", category, e);
            if (meters != null) meters.counter("nexus.retention.category.failures", "category", category).increment();
            return new CategoryResult(category, false, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
