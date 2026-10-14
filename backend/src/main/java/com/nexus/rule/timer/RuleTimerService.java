package com.nexus.rule.timer;

import com.nexus.rule.domain.*;
import com.nexus.rule.persistence.RuleVersionStore;
import com.nexus.rule.trigger.RuleTriggerFactRecorder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rule-owned persistent timers and schedules (C18). Memory holds no deadline: every due occurrence is a PENDING row,
 * consumed idempotently in the same transaction that records its source fact, so two workers cannot create two decisions.
 * Pause, resume, archive and version activation change the execution epoch and so invalidate stale generations here.
 */
@Service
public class RuleTimerService {
    private final JdbcTemplate jdbc;
    private final RuleVersionStore versions;
    private final RuleTriggerFactRecorder recorder;
    private final PlatformTransactionManager transactions;
    private final Clock clock;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private com.nexus.rule.temporal.RuleTemporalRuntime temporal;

    public RuleTimerService(JdbcTemplate jdbc, RuleVersionStore versions, RuleTriggerFactRecorder recorder,
            PlatformTransactionManager transactions, Clock clock, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.jdbc = jdbc; this.versions = versions; this.recorder = recorder; this.transactions = transactions; this.clock = clock; this.mapper = mapper;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setTemporal(@org.springframework.context.annotation.Lazy com.nexus.rule.temporal.RuleTemporalRuntime temporal) { this.temporal = temporal; }

    private static Timestamp ts(Instant at) { return Timestamp.from(at); }

    /** Runs inside the lifecycle (or startup) transaction: invalidates stale generations and arms the owned schedule. */
    public void reconcile(UUID ruleId) {
        var roots = jdbc.queryForList("SELECT lifecycle,active_version_id,execution_epoch FROM rules WHERE rule_id=?", ruleId);
        if (roots.isEmpty()) return;
        var root = roots.getFirst();
        Instant now = clock.instant();
        boolean active = "ACTIVE".equals(root.get("lifecycle")) && root.get("active_version_id") != null;
        UUID activeVersion = (UUID) root.get("active_version_id");
        long epoch = ((Number) root.get("execution_epoch")).longValue();
        if (!active) {
            jdbc.update("UPDATE rule_timers SET status='INVALIDATED',invalidation_reason='RULE_NOT_ACTIVE',updated_at=? WHERE rule_id=? AND status='PENDING'", ts(now), ruleId);
            if (temporal != null) temporal.invalidateRule(ruleId, "RULE_NOT_ACTIVE");
            return;
        }
        jdbc.update("UPDATE rule_timers SET status='INVALIDATED',invalidation_reason='OWNERSHIP_CHANGED',updated_at=? WHERE rule_id=? AND status='PENDING' AND (rule_version_id<>? OR generation<>?)",
            ts(now), ruleId, activeVersion, epoch);
        Rule definition = versions.version(ruleId, activeVersion);
        long revision = jdbc.queryForObject("SELECT revision FROM rule_versions WHERE version_id=?", Long.class, activeVersion);
        armSchedule(definition, activeVersion, revision, epoch, now);
        if (temporal != null) temporal.ownershipChanged(definition, activeVersion, revision, epoch, now);
    }

    private void armSchedule(Rule rule, UUID version, long revision, long epoch, Instant now) {
        var runtime = rule.runtime();
        if (runtime == null || runtime.schedule() == null || !rule.subscribesTo(TriggerType.SCHEDULE)) return;
        UUID scope = explicitScope(rule);
        if (scope == null) return; // validation requires an explicit scope; never arm without one
        var spec = runtime.schedule();
        boolean pending = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM rule_timers WHERE rule_version_id=? AND kind='SCHEDULE' AND generation=? AND status='PENDING')", Boolean.class, version, epoch));
        if (pending) return;
        Instant due;
        if (spec.kind() == ScheduleSpec.Kind.ONE_SHOT) {
            boolean consumed = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM rule_timers WHERE rule_version_id=? AND kind='SCHEDULE' AND status='CONSUMED')", Boolean.class, version));
            if (consumed) return;
            due = spec.at();
        } else {
            long interval = spec.intervalSeconds();
            Instant anchor = spec.startsAt() == null ? now : spec.startsAt();
            if (anchor.isAfter(now)) due = anchor;
            else { long steps = Duration.between(anchor, now).getSeconds() / interval + 1; due = anchor.plusSeconds(steps * interval); }
        }
        insert("SCHEDULE", rule.ruleId(), version, revision, null, scope, epoch, due, 0, Map.of(), now);
    }

    /** Package-visible so the temporal runtime arms its own due occurrences in the same table. */
    public void insert(String kind, UUID rule, UUID version, long revision, String condition, UUID scope, long generation, Instant due,
            long skipped, Map<String, Object> watermark, Instant now) {
        jdbc.update("""
            INSERT INTO rule_timers(timer_id,kind,rule_id,rule_version_id,rule_revision,condition_id,scope_device_id,generation,due_at,watermark,skipped_slots,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)""", UUID.randomUUID(), kind, rule, version, revision, condition, scope, generation, ts(due),
            json(watermark), skipped, ts(now), ts(now));
    }

    /**
     * Takes the pending timer row of one temporal identity before its runtime-state row. Due processing claims the timer
     * first and then locks the state, so every writer uses the same timer-then-state order and cannot deadlock.
     */
    public void lockPending(UUID version, String condition, UUID scope, long generation) {
        jdbc.queryForList("SELECT timer_id FROM rule_timers WHERE rule_version_id=? AND kind='TEMPORAL_DUE' AND condition_id=? AND scope_device_id=? AND generation=? AND status='PENDING' FOR UPDATE",
            version, condition, scope, generation);
    }

    /** Postpones a due timer without consuming it (evidence for the interval is not yet fully applied). */
    public void defer(UUID timerId, Instant until) {
        jdbc.update("UPDATE rule_timers SET next_attempt_at=?,updated_at=? WHERE timer_id=? AND status='PENDING'", ts(until), ts(clock.instant()), timerId);
    }

    /** Invalidates the pending timer of one temporal identity (reset, ownership loss). */
    public void cancelPending(UUID version, String condition, UUID scope, long generation, String reason, Instant now) {
        jdbc.update("UPDATE rule_timers SET status='INVALIDATED',invalidation_reason=?,updated_at=? WHERE rule_version_id=? AND kind='TEMPORAL_DUE' AND condition_id=? AND scope_device_id=? AND generation=? AND status='PENDING'",
            reason, ts(now), version, condition, scope, generation);
    }

    /** Moves the pending deadline in place (rearm) or arms a new one; the pending-identity index keeps it unique. */
    public void rescheduleOrArm(UUID rule, UUID version, long revision, String condition, UUID scope, long generation, Instant due, Map<String, Object> watermark, Instant now) {
        int moved = jdbc.update("UPDATE rule_timers SET due_at=?,watermark=?::jsonb,updated_at=? WHERE rule_version_id=? AND kind='TEMPORAL_DUE' AND condition_id=? AND scope_device_id=? AND generation=? AND status='PENDING'",
            ts(due), json(watermark), ts(now), version, condition, scope, generation);
        if (moved == 0) insert("TEMPORAL_DUE", rule, version, revision, condition, scope, generation, due, 0, watermark, now);
    }

    /** Marks a processed timer; a fact id records the consumption, none records a rechecked-and-reset outcome. */
    public void finish(UUID timerId, UUID factId, String invalidationReason, Instant now) {
        if (factId != null) jdbc.update("UPDATE rule_timers SET status='CONSUMED',consumed_at=?,updated_at=?,consumption_fact_id=? WHERE timer_id=?", ts(now), ts(now), factId, timerId);
        else jdbc.update("UPDATE rule_timers SET status='INVALIDATED',invalidation_reason=?,updated_at=? WHERE timer_id=?", invalidationReason, ts(now), timerId);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static UUID explicitScope(Rule rule) {
        if (rule.conditions() == null) return null;
        for (RuleCondition c : rule.conditions()) if (c.field() == RuleField.DEVICE_ID && c.operator() == RuleOperator.EQ) {
            try { return UUID.fromString(c.expectedValue()); } catch (IllegalArgumentException ignored) { return null; }
        }
        return null;
    }

    @org.springframework.beans.factory.annotation.Value("${nexus.rule.timer-max-attempts:5}")
    private int maxAttempts = 5;
    private static final Logger log = LoggerFactory.getLogger(RuleTimerService.class);

    /**
     * Consumes due timers; safe for concurrent workers and instances. Each timer is isolated: a permanently failing timer
     * records a durable attempt (backoff, then QUARANTINED) and never blocks the independent timers behind it.
     * Row-lock order is global: rules row (shared) -> timer row -> temporal state row, the same order lifecycle changes use.
     * @return occurrences handled (consumed or invalidated, excluding failed attempts).
     */
    public int processDue(int limit) {
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int handled = 0;
        Set<UUID> attempted = new HashSet<>();
        while (handled < limit) {
            Instant now = clock.instant();
            // Non-locking candidate read; the claim below takes locks in the global order.
            var candidates = jdbc.queryForList("""
                SELECT timer_id,rule_id FROM rule_timers
                WHERE status='PENDING' AND due_at<=? AND (next_attempt_at IS NULL OR next_attempt_at<=?)
                ORDER BY due_at,timer_id LIMIT 25""", ts(now), ts(now));
            boolean progressed = false;
            for (var candidate : candidates) {
                UUID timerId = (UUID) candidate.get("timer_id"), ruleId = (UUID) candidate.get("rule_id");
                if (!attempted.add(timerId)) continue;
                try {
                    Boolean claimed = tx.execute(state -> claimAndConsume(timerId, ruleId));
                    if (Boolean.TRUE.equals(claimed)) { handled++; progressed = true; break; }
                } catch (RuntimeException failure) {
                    recordFailure(timerId, failure);       // the claim transaction already rolled back: nothing was consumed
                }
            }
            if (!progressed) break;
            attempted.clear();
        }
        return handled;
    }

    /** Durable, bounded failure bookkeeping in its own transaction; quarantines after the retry limit. */
    void recordFailure(UUID timerId, RuntimeException failure) {
        try {
            var tx = new TransactionTemplate(transactions);
            tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(state -> {
                Instant now = clock.instant();
                var rows = jdbc.queryForList("SELECT attempts,kind,rule_version_id,condition_id,scope_device_id,generation FROM rule_timers WHERE timer_id=? AND status='PENDING' FOR UPDATE", timerId);
                if (rows.isEmpty()) return;
                var row = rows.getFirst();
                int attempts = ((Number) row.get("attempts")).intValue() + 1;
                boolean quarantine = attempts >= maxAttempts;
                long backoff = Math.min(300L, 5L << Math.min(attempts - 1, 6));
                String message = String.valueOf(failure.getClass().getSimpleName() + ": " + failure.getMessage());
                jdbc.update("""
                    UPDATE rule_timers SET attempts=?,last_error=left(?,1000),last_attempt_at=?,next_attempt_at=?,updated_at=?,
                        status=CASE WHEN ? THEN 'QUARANTINED' ELSE status END,
                        invalidation_reason=CASE WHEN ? THEN 'QUARANTINED_AFTER_FAILURES' ELSE invalidation_reason END
                    WHERE timer_id=?""", attempts, message, ts(now), ts(now.plusSeconds(backoff)), ts(now), quarantine, quarantine, timerId);
                if (quarantine && "TEMPORAL_DUE".equals(row.get("kind"))) {
                    // The deadline will never fire: progress that depended on it is invalid, not silently kept.
                    jdbc.update("UPDATE rule_temporal_state SET phase='IDLE',members='[]'::jsonb,armed_at=NULL,due_at=NULL,coverage_end=NULL,reset_reason='TIMER_QUARANTINED',updated_at=? WHERE rule_version_id=? AND condition_id=? AND scope_device_id=? AND generation=?",
                        ts(now), row.get("rule_version_id"), row.get("condition_id"), row.get("scope_device_id"), row.get("generation"));
                }
                if (quarantine) log.error("RULE_TIMER_QUARANTINED {} after {} attempts: {}", timerId, attempts, message);
                else log.warn("RULE_TIMER_ATTEMPT_FAILED {} attempt {}: {}", timerId, attempts, message);
            });
        } catch (RuntimeException bookkeeping) {
            log.error("RULE_TIMER_FAILURE_NOT_RECORDED {}", timerId, bookkeeping);
        }
    }

    /** @return true when this worker owned and handled the timer; false when another worker holds or already handled it. */
    private Boolean claimAndConsume(UUID timerId, UUID ruleId) {
        Instant now = clock.instant();
        // 1. rules row (shared), 2. timer row, 3. (inside temporal) state row - the lifecycle service uses the same order.
        var owner = jdbc.queryForList("SELECT lifecycle,active_version_id,execution_epoch,reconciliation FROM rules WHERE rule_id=? FOR SHARE", ruleId);
        var rows = jdbc.queryForList("SELECT * FROM rule_timers WHERE timer_id=? AND status='PENDING' AND due_at<=? FOR UPDATE SKIP LOCKED", timerId, ts(now));
        if (rows.isEmpty()) return false;
        var timer = rows.getFirst();
        UUID id = (UUID) timer.get("timer_id"), rule = (UUID) timer.get("rule_id"), version = (UUID) timer.get("rule_version_id");
        long generation = ((Number) timer.get("generation")).longValue();
        Instant due = ((Timestamp) timer.get("due_at")).toInstant();
        boolean owned = !owner.isEmpty() && "ACTIVE".equals(owner.getFirst().get("lifecycle")) && version.equals(owner.getFirst().get("active_version_id"))
            && generation == ((Number) owner.getFirst().get("execution_epoch")).longValue()
            && Set.of("PASSED", "NOT_REQUIRED").contains(String.valueOf(owner.getFirst().get("reconciliation")));
        if (!owned) {
            jdbc.update("UPDATE rule_timers SET status='INVALIDATED',invalidation_reason='OWNERSHIP_CHANGED_BEFORE_DUE',updated_at=? WHERE timer_id=?", ts(now), id);
            return true;
        }
        UUID scope = (UUID) timer.get("scope_device_id");
        long revision = ((Number) timer.get("rule_revision")).longValue();
        String kind = (String) timer.get("kind");
        if ("SCHEDULE".equals(kind)) {
            Rule definition = versions.version(rule, version);
            var spec = definition.runtime().schedule();
            long skipped = 0;
            Instant next = null;
            if (spec.kind() == ScheduleSpec.Kind.FIXED_INTERVAL) {
                long interval = spec.intervalSeconds();
                skipped = Math.max(0, Duration.between(due, now).getSeconds() / interval);   // whole missed slots after this one
                next = due.plusSeconds((skipped + 1) * interval);
            }
            var attributes = new LinkedHashMap<String, Object>();
            attributes.put("ruleId", rule.toString()); attributes.put("ruleVersionId", version.toString());
            attributes.put("scheduleKind", spec.kind().name()); attributes.put("slot", due.toString()); attributes.put("skippedSlots", skipped);
            var fact = recorder.recordAndRoute(new RuleTriggerFactRecorder.Fact(TriggerType.SCHEDULE, id.toString(), scope, due,
                Map.of("timerId", id.toString(), "generation", generation), attributes, null));
            jdbc.update("UPDATE rule_timers SET status='CONSUMED',consumed_at=?,updated_at=?,skipped_slots=?,consumption_fact_id=? WHERE timer_id=?",
                ts(now), ts(now), skipped, fact.orElse(null), id);
            if (next != null) insert("SCHEDULE", rule, version, revision, null, scope, generation, next, 0, Map.of(), now);
            return true;
        }
        if (temporal == null) {
            jdbc.update("UPDATE rule_timers SET status='INVALIDATED',invalidation_reason='TEMPORAL_RUNTIME_UNAVAILABLE',updated_at=? WHERE timer_id=?", ts(now), id);
            return true;
        }
        temporal.consumeDue(timer, now);
        return true;
    }
}
