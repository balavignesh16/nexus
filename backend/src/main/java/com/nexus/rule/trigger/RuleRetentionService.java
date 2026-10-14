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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, conservative cleanup of terminal Phase 3 runtime records. Each statement deletes at most {@code batch} rows in its own
 * transaction and the run is capped, so a backlog drains over several runs without long locks. Nothing is removed that is still
 * referenced as evidence or needed by an active runtime:
 * <ul>
 * <li>facts: only ROUTED/EXPIRED/QUARANTINED facts that no RuleMatch, plan, timer consumption or chain rejection references (a fact that
 *     decided something is history and stays);</li>
 * <li>timers: INVALIDATED/QUARANTINED/CONSUMED rows, except consumed ONE_SHOT schedules (they remember "already fired");</li>
 * <li>temporal state: only non-ARMED rows of superseded generations or archived rules;</li>
 * <li>temporal evidence and chain rejections by age.</li>
 * </ul>
 */
@Component
public class RuleRetentionService {
    private static final Logger log = LoggerFactory.getLogger(RuleRetentionService.class);
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final Clock clock;
    private final Duration factAge, terminalTimerAge, quarantineAge, evidenceAge;
    private final int batch;
    private static final int MAX_BATCHES_PER_TABLE = 20;

    public RuleRetentionService(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock,
            @Value("${nexus.rule.retention.fact-days:14}") long factDays,
            @Value("${nexus.rule.retention.timer-days:14}") long timerDays,
            @Value("${nexus.rule.retention.quarantine-days:60}") long quarantineDays,
            @Value("${nexus.rule.retention.evidence-days:30}") long evidenceDays,
            @Value("${nexus.rule.retention.batch:500}") int batch) {
        this.jdbc = jdbc; this.transactions = transactions; this.clock = clock;
        this.factAge = Duration.ofDays(factDays); this.terminalTimerAge = Duration.ofDays(timerDays);
        this.quarantineAge = Duration.ofDays(quarantineDays); this.evidenceAge = Duration.ofDays(evidenceDays);
        this.batch = Math.max(1, batch);
    }

    @Scheduled(fixedDelayString = "${nexus.rule.retention-scan-ms:3600000}", initialDelayString = "${nexus.rule.retention-initial-delay-ms:600000}")
    public void scheduled() {
        try { purge(); } catch (RuntimeException failure) { log.warn("Rule retention unavailable: {}", failure.getMessage()); }
    }

    private static Timestamp ts(Instant at) { return Timestamp.from(at); }

    private int batched(String sql, Object... args) {
        int total = 0;
        var template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (int i = 0; i < MAX_BATCHES_PER_TABLE; i++) {
            Integer deleted = template.execute(s -> jdbc.update(sql, args));
            int n = deleted == null ? 0 : deleted;
            total += n;
            if (n < batch) break;
        }
        return total;
    }

    /** @return rows removed per table in this run. */
    public Map<String, Integer> purge() {
        Instant now = clock.instant();
        var removed = new LinkedHashMap<String, Integer>();
        removed.put("chainRejections", batched("""
            DELETE FROM rule_chain_rejections WHERE rejection_id IN (SELECT rejection_id FROM rule_chain_rejections WHERE rejected_at < ? LIMIT ?)""",
            ts(now.minus(factAge)), batch));
        // Timers first: they reference facts (consumption_fact_id), never the other way round.
        removed.put("timers", batched("""
            DELETE FROM rule_timers WHERE timer_id IN (
                SELECT t.timer_id FROM rule_timers t JOIN rule_versions v ON v.version_id=t.rule_version_id
                WHERE ((t.status='INVALIDATED' AND t.updated_at < ?)
                    OR (t.status='CONSUMED' AND t.updated_at < ? AND NOT (t.kind='SCHEDULE' AND v.definition->'runtime'->'schedule'->>'kind'='ONE_SHOT'))
                    OR (t.status='QUARANTINED' AND t.updated_at < ?))
                LIMIT ?)""", ts(now.minus(terminalTimerAge)), ts(now.minus(terminalTimerAge)), ts(now.minus(quarantineAge)), batch));
        removed.put("facts", batched("""
            DELETE FROM rule_trigger_facts WHERE fact_id IN (
                SELECT f.fact_id FROM rule_trigger_facts f
                WHERE ((f.routing_status IN ('ROUTED','EXPIRED') AND f.recorded_at < ?) OR (f.routing_status='QUARANTINED' AND f.recorded_at < ?))
                  AND NOT EXISTS (SELECT 1 FROM rule_matches m WHERE m.trigger_fact_id=f.fact_id)
                  AND NOT EXISTS (SELECT 1 FROM rule_action_plans p WHERE p.fact_id=f.fact_id)
                  AND NOT EXISTS (SELECT 1 FROM rule_timers t WHERE t.consumption_fact_id=f.fact_id)
                  AND NOT EXISTS (SELECT 1 FROM rule_chain_rejections c WHERE c.fact_id=f.fact_id)
                LIMIT ?)""", ts(now.minus(factAge)), ts(now.minus(quarantineAge)), batch));
        removed.put("temporalState", batched("""
            DELETE FROM rule_temporal_state WHERE ctid = ANY(ARRAY(
                SELECT s.ctid FROM rule_temporal_state s JOIN rules r ON r.rule_id=s.rule_id
                WHERE s.phase<>'ARMED' AND s.updated_at < ?
                  AND (r.lifecycle='ARCHIVED' OR s.generation < r.execution_epoch OR s.rule_version_id IS DISTINCT FROM r.active_version_id)
                LIMIT ?))""", ts(now.minus(terminalTimerAge)), batch));
        removed.put("temporalEvidence", batched("""
            DELETE FROM rule_temporal_evidence WHERE seq IN (SELECT seq FROM rule_temporal_evidence WHERE occurred_at < ? LIMIT ?)""",
            ts(now.minus(evidenceAge)), batch));
        if (removed.values().stream().anyMatch(n -> n > 0)) log.info("RULE_RETENTION removed {}", removed);
        return removed;
    }
}
