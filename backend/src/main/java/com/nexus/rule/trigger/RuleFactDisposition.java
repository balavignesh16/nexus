package com.nexus.rule.trigger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable routing disposition of a committed trigger fact: PENDING -> ROUTED, or FAILED with bounded attempts and backoff and
 * finally QUARANTINED. Every update is its own transaction because routing runs in the afterCommit phase of the source
 * transaction, where a joined transaction would never commit. This is bookkeeping about rule evaluation only; it never
 * resends a command or publishes anything.
 */
@Component
public class RuleFactDisposition {
    private static final Logger log = LoggerFactory.getLogger(RuleFactDisposition.class);
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final Clock clock;
    @Value("${nexus.rule.fact-max-attempts:5}") private int maxAttempts = 5;

    public RuleFactDisposition(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = jdbc; this.transactions = transactions; this.clock = clock;
    }

    private TransactionTemplate fresh() {
        var template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
    private static Timestamp ts(Instant at) { return Timestamp.from(at); }

    public void attempt(UUID factId) {
        fresh().executeWithoutResult(s -> jdbc.update("UPDATE rule_trigger_facts SET routing_attempts=routing_attempts+1 WHERE fact_id=? AND routing_status<>'ROUTED'", factId));
    }

    public boolean temporalApplied(UUID factId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT temporal_applied_at IS NOT NULL FROM rule_trigger_facts WHERE fact_id=?", Boolean.class, factId));
    }

    public void markTemporalApplied(UUID factId) {
        fresh().executeWithoutResult(s -> jdbc.update("UPDATE rule_trigger_facts SET temporal_applied_at=COALESCE(temporal_applied_at,?) WHERE fact_id=?", ts(clock.instant()), factId));
    }

    public void routed(UUID factId) {
        fresh().executeWithoutResult(s -> jdbc.update("UPDATE rule_trigger_facts SET routing_status='ROUTED',routed_at=?,routing_error=NULL,next_attempt_at=NULL WHERE fact_id=? AND routing_status<>'QUARANTINED'", ts(clock.instant()), factId));
    }

    /** Routed, with an audit note (for example rules that recovery did not admit because their authority changed since recording). */
    public void routedWithNote(UUID factId, String note) {
        fresh().executeWithoutResult(s -> jdbc.update("UPDATE rule_trigger_facts SET routing_status='ROUTED',routed_at=?,routing_error=left(?,1000),next_attempt_at=NULL WHERE fact_id=? AND routing_status<>'QUARANTINED'", ts(clock.instant()), note, factId));
    }

    /** @return true when the fact has just been quarantined (retry budget exhausted). */
    public boolean failed(UUID factId, Throwable failure) {
        try {
            return Boolean.TRUE.equals(fresh().execute(s -> {
                Instant now = clock.instant();
                var rows = jdbc.queryForList("SELECT routing_attempts FROM rule_trigger_facts WHERE fact_id=? AND routing_status<>'ROUTED' FOR UPDATE", factId);
                if (rows.isEmpty()) return false;
                int attempts = ((Number) rows.getFirst().get("routing_attempts")).intValue();
                boolean quarantine = attempts >= maxAttempts;
                long backoff = Math.min(300L, 5L << Math.min(Math.max(attempts - 1, 0), 6));
                String message = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                jdbc.update("UPDATE rule_trigger_facts SET routing_status=?,routing_error=left(?,1000),next_attempt_at=? WHERE fact_id=?",
                    quarantine ? "QUARANTINED" : "FAILED", message, quarantine ? null : ts(now.plusSeconds(backoff)), factId);
                if (quarantine) log.error("RULE_FACT_QUARANTINED {} after {} attempts: {}", factId, attempts, message);
                return quarantine;
            }));
        } catch (RuntimeException bookkeeping) {
            log.error("RULE_FACT_DISPOSITION_NOT_RECORDED {}", factId, bookkeeping);
            return false;
        }
    }

    /**
     * Terminal: a delayed recovery found the fact no longer eligible to produce matches (it was never evaluated). The reason and the three
     * times (occurrence, recording, this evaluation) are kept so the decision is auditable. Nothing is dispatched or planned.
     */
    public void expired(UUID factId, String reason, Instant occurredAt, Instant recordedAt) {
        Instant now = clock.instant();
        String note = "EXPIRED:" + reason + " occurredAt=" + occurredAt + " recordedAt=" + recordedAt + " evaluatedAt=" + now;
        fresh().executeWithoutResult(s -> jdbc.update("UPDATE rule_trigger_facts SET routing_status='EXPIRED',routing_error=left(?,1000),routed_at=?,next_attempt_at=NULL WHERE fact_id=? AND routing_status IN ('PENDING','FAILED')", note, ts(now), factId));
        log.warn("RULE_FACT_EXPIRED {}: {}", factId, note);
    }

    /** Leaves the fact PENDING for the recovery scanner and records why (for example a full chain queue). */
    public void deferred(UUID factId, String reason) {
        try {
            fresh().executeWithoutResult(s -> jdbc.update("UPDATE rule_trigger_facts SET routing_error=left(?,1000) WHERE fact_id=? AND routing_status='PENDING'", reason, factId));
        } catch (RuntimeException bookkeeping) {
            log.error("RULE_FACT_DISPOSITION_NOT_RECORDED {}", factId, bookkeeping);
        }
    }
}
