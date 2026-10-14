package com.nexus.rule.trigger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bounded recovery of committed facts whose post-commit routing did not complete (crash, queue overflow, transient failure).
 * It re-runs the same idempotent router; it never replays a command and never touches ROUTED or QUARANTINED facts. A short
 * lease (next_attempt_at) lets several instances share the work without routing one fact concurrently.
 */
@Component
public class RuleFactRecoveryScanner {
    private static final Logger log = LoggerFactory.getLogger(RuleFactRecoveryScanner.class);
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final RuleTriggerRouter router;
    private final Clock clock;
    @Value("${nexus.rule.fact-recovery-grace-seconds:30}") private long graceSeconds = 30;

    public RuleFactRecoveryScanner(JdbcTemplate jdbc, PlatformTransactionManager transactions, RuleTriggerRouter router, Clock clock) {
        this.jdbc = jdbc; this.transactions = transactions; this.router = router; this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${nexus.rule.fact-recovery-scan-ms:10000}")
    public void scheduled() {
        try { recover(100); } catch (RuntimeException failure) { log.warn("Rule fact recovery scan unavailable: {}", failure.getMessage()); }
    }

    /** @return number of facts claimed and routed successfully. */
    public int recover(int limit) {
        Instant now = clock.instant();
        var template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<UUID> claimed = template.execute(s -> jdbc.queryForList("""
            UPDATE rule_trigger_facts SET next_attempt_at=?
            WHERE fact_id IN (SELECT fact_id FROM rule_trigger_facts
                WHERE routing_status IN ('PENDING','FAILED') AND recorded_at <= ?
                  AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                ORDER BY recorded_at, fact_id LIMIT ? FOR UPDATE SKIP LOCKED)
            RETURNING fact_id""", UUID.class, Timestamp.from(now.plusSeconds(60)), Timestamp.from(now.minusSeconds(graceSeconds)), Timestamp.from(now), limit));
        int recovered = 0;
        for (UUID factId : claimed == null ? List.<UUID>of() : claimed) {
            try { if (router.route(factId, true)) recovered++; }
            catch (RuntimeException failure) { log.warn("RULE_FACT_RECOVERY_FAILED {}: {}", factId, failure.getMessage()); }
        }
        return recovered;
    }
}
