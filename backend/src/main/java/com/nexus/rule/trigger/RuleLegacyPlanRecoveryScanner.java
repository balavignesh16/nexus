package com.nexus.rule.trigger;

import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.DomainEventRepository;
import com.nexus.rule.domain.*;
import com.nexus.rule.persistence.RuleVersionStore;
import com.nexus.rule.plan.PlanSource;
import com.nexus.rule.plan.RuleActionPlanner;
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
import java.util.*;

/**
 * Bounded recovery of a legacy batch whose RuleMatches were persisted but whose ActionPlan was never created (plan write failure, crash
 * between the matches and the plan). The batch is rebuilt from PERSISTED evidence only: the committed DomainEvent, and for every match its
 * immutable rule version at the pinned revision and epoch. The same arbitration planner then decides it. Nothing here evaluates a rule,
 * persists a match, or touches cooldown / EDGE_TRUE / hysteresis state, and a batch that already has a plan is never recovered, so no command
 * is repeated. There is no outbox and no resend: a plan persisted before a crash and never dispatched stays the declared M-B boundary.
 * <p>
 * Fail closed: too old (a late command on a stale event would be new intent), ownership of every match lost, missing event or version,
 * or an unattributed match are recorded durably (EXPIRED / UNRECOVERABLE) instead of being reconstructed from current state.
 */
@Component
public class RuleLegacyPlanRecoveryScanner {
    private static final Logger log = LoggerFactory.getLogger(RuleLegacyPlanRecoveryScanner.class);
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final Clock clock;
    private final RuleVersionStore versions;
    private final RuleActionPlanner planner;
    private final DomainEventRepository events;
    @Value("${nexus.rule.legacy-plan-recovery-grace-seconds:30}") private long graceSeconds = 30;
    @Value("${nexus.rule.legacy-plan-recovery-max-age-seconds:120}") private long maxAgeSeconds = 120;
    @Value("${nexus.rule.legacy-plan-recovery-max-attempts:5}") private int maxAttempts = 5;

    public RuleLegacyPlanRecoveryScanner(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock, RuleVersionStore versions,
            @org.springframework.context.annotation.Lazy RuleActionPlanner planner, DomainEventRepository events) {
        this.jdbc = jdbc; this.transactions = transactions; this.clock = clock; this.versions = versions; this.planner = planner; this.events = events;
    }

    @Scheduled(fixedDelayString = "${nexus.rule.legacy-plan-recovery-scan-ms:15000}")
    public void scheduled() {
        try { recover(50); } catch (RuntimeException failure) { log.warn("Legacy plan recovery scan unavailable: {}", failure.getMessage()); }
    }

    private TransactionTemplate fresh() {
        var template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
    private static Timestamp ts(Instant at) { return Timestamp.from(at); }

    /** @return the number of batches whose plan this call created. */
    public int recover(int limit) {
        Instant now = clock.instant();
        // Discovery: committed legacy matches with no plan, whose newest match is older than the grace period (the inline flow has had its chance).
        fresh().executeWithoutResult(s -> jdbc.update("""
            INSERT INTO rule_legacy_plan_recovery(event_id,status,attempts,next_attempt_at,discovered_at,updated_at)
            SELECT m.event_id,'PENDING',0,?,?,? FROM rule_matches m
            WHERE m.event_id IS NOT NULL AND m.trigger_fact_id IS NULL
              AND NOT EXISTS (SELECT 1 FROM rule_action_plans p WHERE p.event_id=m.event_id)
              AND NOT EXISTS (SELECT 1 FROM rule_legacy_plan_recovery r WHERE r.event_id=m.event_id)
            GROUP BY m.event_id HAVING max(m.matched_at) <= ?
            ON CONFLICT (event_id) DO NOTHING""", ts(now), ts(now), ts(now), ts(now.minusSeconds(graceSeconds))));
        // Claim with a short lease; the attempt is counted at claim time so even a crash mid-recovery stays bounded.
        List<UUID> claimed = fresh().execute(s -> jdbc.queryForList("""
            UPDATE rule_legacy_plan_recovery SET attempts=attempts+1,next_attempt_at=?,updated_at=?
            WHERE event_id IN (SELECT event_id FROM rule_legacy_plan_recovery WHERE status IN ('PENDING','FAILED') AND next_attempt_at<=?
                               ORDER BY next_attempt_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED)
            RETURNING event_id""", UUID.class, ts(now.plusSeconds(60)), ts(now), ts(now), limit));
        int created = 0;
        for (UUID eventId : claimed == null ? List.<UUID>of() : claimed) {
            try { if (recoverOne(eventId, now)) created++; }
            catch (RuntimeException failure) { failed(eventId, failure, now); }
        }
        return created;
    }

    /** A terminal, recorded outcome (never retried): the batch cannot or must not be decided now. */
    private static final class Unrecoverable extends RuntimeException {
        private final String status, reason;
        Unrecoverable(String status, String reason) { super(status + ":" + reason, null, false, false); this.status = status; this.reason = reason; }
        String status() { return status; }
        String reason() { return reason; }
    }

    private boolean recoverOne(UUID eventId, Instant now) {
        try {
            return rebuildAndPlan(eventId, now);
        } catch (Unrecoverable terminal) {
            finish(eventId, terminal.status(), terminal.reason(), now);
            log.warn("LEGACY_PLAN_NOT_RECOVERED {} {} {}", eventId, terminal.status(), terminal.reason());
            return false;
        }
    }

    private boolean rebuildAndPlan(UUID eventId, Instant now) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rule_action_plans WHERE event_id=?)", Boolean.class, eventId))) {
            finish(eventId, "RECOVERED", "PLAN_ALREADY_EXISTS", now);          // another path decided it: reuse, never decide twice
            return false;
        }
        var rows = jdbc.queryForList("""
            SELECT match_id,rule_id,rule_version_id,rule_revision,execution_epoch,matched_at,correlation_id
            FROM rule_matches WHERE event_id=? AND trigger_fact_id IS NULL ORDER BY matched_at,match_id""", eventId);
        if (rows.isEmpty()) throw new Unrecoverable("UNRECOVERABLE", "NO_PERSISTED_MATCH");
        Instant newest = rows.stream().map(r -> ((Timestamp) r.get("matched_at")).toInstant()).max(Comparator.naturalOrder()).orElseThrow();
        if (newest.isBefore(now.minusSeconds(maxAgeSeconds))) throw new Unrecoverable("EXPIRED", "AGE_EXCEEDED");
        DomainEvent event = events.findById(eventId).orElseThrow(() -> new Unrecoverable("UNRECOVERABLE", "SOURCE_EVENT_MISSING"));

        var batch = new ArrayList<RuleMatchedEvent>();
        boolean anyOwned = false;
        for (var row : rows) {
            UUID ruleId = (UUID) row.get("rule_id"), versionId = (UUID) row.get("rule_version_id");
            if (versionId == null || row.get("rule_revision") == null || row.get("execution_epoch") == null) throw new Unrecoverable("UNRECOVERABLE", "UNATTRIBUTED_MATCH");
            long revision = ((Number) row.get("rule_revision")).longValue(), epoch = ((Number) row.get("execution_epoch")).longValue();
            var stored = jdbc.queryForList("SELECT revision FROM rule_versions WHERE rule_id=? AND version_id=?", ruleId, versionId);
            if (stored.isEmpty() || ((Number) stored.getFirst().get("revision")).longValue() != revision) throw new Unrecoverable("UNRECOVERABLE", "VERSION_MISMATCH");
            Rule definition;
            try { definition = versions.version(ruleId, versionId); }
            catch (RuntimeException unreadable) { throw new Unrecoverable("UNRECOVERABLE", "VERSION_UNREADABLE"); }
            Rule pinned = new Rule(ruleId, definition.name(), true, definition.priority(), definition.conditions(), definition.actions(), definition.metadata(),
                definition.cooldownSeconds(), new RuleExecutionIdentity(versionId, revision, epoch), definition.runtime());
            var owner = jdbc.queryForList("SELECT lifecycle,active_version_id,execution_epoch,reconciliation FROM rules WHERE rule_id=?", ruleId);
            anyOwned |= !owner.isEmpty() && "ACTIVE".equals(owner.getFirst().get("lifecycle")) && versionId.equals(owner.getFirst().get("active_version_id"))
                && epoch == ((Number) owner.getFirst().get("execution_epoch")).longValue() && Set.of("PASSED", "NOT_REQUIRED").contains(owner.getFirst().get("reconciliation"));
            String correlation = row.get("correlation_id") == null ? null : row.get("correlation_id").toString();
            batch.add(new RuleMatchedEvent((UUID) row.get("match_id"), ((Timestamp) row.get("matched_at")).toInstant(),
                new RuleEvaluationResult(true, pinned, event, "Recovered persisted match"), correlation));
        }
        // Every owner changed: nothing could be authorized any more, and a plan of only denied candidates would be noise.
        if (!anyOwned) throw new Unrecoverable("EXPIRED", "OWNERSHIP_CHANGED");
        boolean created = planner.planAndDispatch(PlanSource.event(eventId), batch);
        finish(eventId, "RECOVERED", created ? "PLAN_CREATED" : "PLAN_ALREADY_EXISTS", now);
        return created;
    }

    private void finish(UUID eventId, String status, String reason, Instant now) {
        fresh().executeWithoutResult(s -> jdbc.update("UPDATE rule_legacy_plan_recovery SET status=?,reason=?,last_error=NULL,next_attempt_at=NULL,updated_at=? WHERE event_id=?",
            status, reason, ts(now), eventId));
    }

    private void failed(UUID eventId, RuntimeException failure, Instant now) {
        try {
            fresh().executeWithoutResult(s -> {
                int attempts = jdbc.queryForObject("SELECT attempts FROM rule_legacy_plan_recovery WHERE event_id=? FOR UPDATE", Integer.class, eventId);
                boolean quarantine = attempts >= maxAttempts;
                long backoff = Math.min(300L, 5L << Math.min(Math.max(attempts - 1, 0), 6));
                jdbc.update("UPDATE rule_legacy_plan_recovery SET status=?,last_error=left(?,1000),next_attempt_at=?,updated_at=? WHERE event_id=?",
                    quarantine ? "QUARANTINED" : "FAILED", failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                    quarantine ? null : ts(now.plus(Duration.ofSeconds(backoff))), ts(now), eventId);
                if (quarantine) log.error("LEGACY_PLAN_RECOVERY_QUARANTINED {} after {} attempts: {}", eventId, attempts, failure.getMessage());
            });
        } catch (RuntimeException bookkeeping) {
            log.error("LEGACY_PLAN_RECOVERY_NOT_RECORDED {}", eventId, bookkeeping);
        }
    }
}
