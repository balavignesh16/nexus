package com.nexus.rule.temporal;

import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.rule.application.RuleEvaluationSnapshotService;
import com.nexus.rule.application.RuleEvaluator;
import com.nexus.rule.domain.*;
import com.nexus.rule.snapshot.CapturedRule;
import com.nexus.rule.snapshot.RuleEvaluationContext;
import com.nexus.rule.snapshot.RuleEvaluationSnapshot;
import com.nexus.rule.timer.RuleTimerService;
import com.nexus.rule.trigger.RuleTriggerFactRecorder;
import com.nexus.rule.trigger.TriggerObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Durable runtime for the C15 temporal operators. State identity is ruleVersionId + conditionId + scope + generation
 * (the execution epoch). Every transition is persisted with inspectable evidence; deadlines live in rule_timers, never in
 * memory. Qualified evidence comes only from the frozen snapshot (R1/U1 unit safety and P2.3 quality are part of
 * "eligible"), and a satisfied operator emits a TEMPORAL_DUE source fact which the normal pipeline evaluates, persists
 * as a match and authorizes through a persisted plan.
 */
@Service
public class RuleTemporalRuntime {
    private static final Logger log = LoggerFactory.getLogger(RuleTemporalRuntime.class);
    static final int MAX_MEMBERS = 200;

    private final RuleEvaluationSnapshotService snapshots;
    private final RuleEvaluator evaluator;
    private final TemporalStateStore store;
    private final RuleTimerService timers;
    private final RuleTriggerFactRecorder recorder;
    private final PlatformTransactionManager transactions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RuleTemporalRuntime(RuleEvaluationSnapshotService snapshots, RuleEvaluator evaluator, TemporalStateStore store,
            @org.springframework.context.annotation.Lazy RuleTimerService timers, RuleTriggerFactRecorder recorder,
            PlatformTransactionManager transactions, JdbcTemplate jdbc, Clock clock) {
        this.snapshots = snapshots; this.evaluator = evaluator; this.store = store; this.timers = timers; this.recorder = recorder;
        this.transactions = transactions; this.jdbc = jdbc; this.clock = clock;
    }

    // ---------------------------------------------------------------- ownership (inside the lifecycle transaction)

    /** Idempotent: creates the current-generation rows, drops stale progress and arms activation-based operators. */
    public void ownershipChanged(Rule definition, UUID version, long revision, long generation, Instant now) {
        store.invalidateStale(definition.ruleId(), version, generation, now);
        UUID scope = RuleTimerService.explicitScope(definition);
        if (scope == null) return;
        for (RuleCondition c : definition.conditions()) {
            if (c.temporal() == null) continue;
            var state = store.lockOrCreate(definition.ruleId(), version, revision, c.conditionId(), scope, generation, c.temporal().operator().name(), now);
            if (state.watermark().containsKey("activatedAt")) continue;
            var watermark = new LinkedHashMap<>(state.watermark());
            watermark.put("activatedAt", now.toString());
            TemporalRuntimeEvidence next = state;
            long duration = c.temporal().durationSeconds() == null ? 0 : c.temporal().durationSeconds();
            if (c.temporal().operator() == TemporalOperator.ABSENCE_FOR) {
                next = copy(state, "ARMED", now, micro(now.plusSeconds(duration)), null, List.of(), watermark, state.sequence(), null);
                timers.rescheduleOrArm(definition.ruleId(), version, revision, c.conditionId(), scope, generation, next.dueAt(), Map.of("armedBy", "ACTIVATION"), now);
                store.save(next, now);
                store.evidence(next, "ARMED", now, Map.of("reason", "ACTIVATION_OR_RESUME", "quality", "NO_OBSERVATION_YET"));
                continue;
            }
            if (c.temporal().operator() == TemporalOperator.OFFLINE_FOR) {
                var rows = jdbc.queryForList("SELECT status,last_changed_at FROM device_connectivity WHERE device_id=?", scope);
                if (!rows.isEmpty() && DeviceConnectivityStatus.OFFLINE.name().equals(rows.getFirst().get("status"))) {
                    Instant since = ((java.sql.Timestamp) rows.getFirst().get("last_changed_at")).toInstant();
                    watermark.put("offlineSince", since.toString());
                    next = copy(state, "ARMED", now, micro(now.plusSeconds(duration)), null, List.of(), watermark, state.sequence(), null);
                    timers.rescheduleOrArm(definition.ruleId(), version, revision, c.conditionId(), scope, generation, next.dueAt(), Map.of("armedBy", "ACTIVATION_OFFLINE"), now);
                    store.save(next, now);
                    store.evidence(next, "ARMED", now, Map.of("reason", "ACTIVATION_WHILE_OBSERVED_OFFLINE", "offlineSince", since.toString()));
                    continue;
                }
            }
            store.save(copy(state, state.phase(), state.armedAt(), state.dueAt(), state.coverageEnd(), state.members(), watermark, state.sequence(), state.resetReason()), now);
        }
    }

    public void invalidateRule(UUID ruleId, String reason) {
        store.invalidateRule(ruleId, reason, clock.instant());
    }

    // ---------------------------------------------------------------- observation of committed facts

    /** Applies one committed ACCEPTED_TELEMETRY or CONNECTIVITY_CHANGED fact to every temporal condition scoped to its device. */
    public void observe(TriggerObservation fact) {
        if (fact.type() != TriggerType.ACCEPTED_TELEMETRY && fact.type() != TriggerType.CONNECTIVITY_CHANGED) return;
        Instant now = clock.instant();
        RuleEvaluationSnapshot snapshot = snapshots.acquireTemporal(fact, null, now);
        RuntimeException firstFailure = null;
        for (CapturedRule captured : snapshot.rules()) {
            if (!captured.executable() || captured.scopeDeviceId() == null || captured.rule().executionIdentity() == null) continue;
            RuleCondition condition = temporalCondition(captured.rule());
            if (condition == null) continue;
            var context = new RuleEvaluationContext(UUID.randomUUID(), snapshot, captured, fact);
            try {
                // Runs inside the source transaction's afterCommit phase, where a REQUIRED template would join the finished
                // transaction and its synchronizations would never fire. Each state update is its own new transaction.
                var template = new TransactionTemplate(transactions);
                template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                template.executeWithoutResult(tx -> applyFact(captured, condition, context, fact, now));
            } catch (RuntimeException failure) {
                // Never swallowed: the router records the fact as FAILED, retries it, and quarantines (resetting continuity) if it cannot be applied.
                log.error("RULE_TEMPORAL_OBSERVATION_FAILED rule={} fact={}", captured.rule().ruleId(), fact.factId(), failure);
                if (firstFailure == null) firstFailure = failure; else firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure != null) throw firstFailure;
    }

    /** Fail closed: any continuity, count or trend progress for this device is invalid once an observation can no longer be applied. */
    public void invalidateDevice(UUID deviceId, String reason) {
        var template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(tx -> {
            Instant now = clock.instant();
            // lock order: timer rows, then state rows
            jdbc.update("UPDATE rule_timers SET status='INVALIDATED',invalidation_reason=?,updated_at=? WHERE kind='TEMPORAL_DUE' AND scope_device_id=? AND status='PENDING' AND condition_id IN (SELECT condition_id FROM rule_temporal_state WHERE scope_device_id=? AND operator<>'ABSENCE_FOR' AND operator<>'OFFLINE_FOR')",
                reason, java.sql.Timestamp.from(now), deviceId, deviceId);
            jdbc.update("UPDATE rule_temporal_state SET phase='IDLE',members='[]'::jsonb,armed_at=NULL,due_at=NULL,coverage_end=NULL,reset_reason=?,updated_at=? WHERE scope_device_id=? AND phase='ARMED' AND operator NOT IN ('ABSENCE_FOR','OFFLINE_FOR')",
                reason, java.sql.Timestamp.from(now), deviceId);
        });
    }

    /** True when an accepted-telemetry or connectivity fact of this device inside the interval has not yet been applied to temporal state. */
    private boolean hasUnappliedEvidence(UUID deviceId, Instant from, Instant to, String exceptFactId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM rule_trigger_facts WHERE device_id=? AND trigger_type IN ('ACCEPTED_TELEMETRY','CONNECTIVITY_CHANGED')
                AND temporal_applied_at IS NULL AND routing_status<>'QUARANTINED' AND recorded_at > ? AND recorded_at <= ? AND (?::text IS NULL OR fact_id::text<>?))""",
            Boolean.class, deviceId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to), exceptFactId, exceptFactId));
    }

    private static RuleCondition temporalCondition(Rule rule) {
        return rule.conditions().stream().filter(c -> c.temporal() != null).findFirst().orElse(null);
    }

    private record Sample(boolean present, boolean eligible, boolean met, BigDecimal number, Instant receivedAt, String unit, String quality, String reason, int threshold) {}

    /** The observation's own sample (empty when it cannot be reconstructed), or the frozen current state for a due-time recheck. */
    private Optional<Sample> sampleOf(RuleEvaluationContext context, RuleCondition condition, UUID scope, TriggerObservation fact) {
        if (condition.temporal().operator() == TemporalOperator.OFFLINE_FOR) return Optional.of(sample(context, condition, scope));
        if (fact.type() == TriggerType.CONNECTIVITY_CHANGED) {
            // The status change itself is the evidence: OFFLINE disqualifies positive progress, ONLINE adds nothing.
            boolean offline = "OFFLINE".equals(String.valueOf(fact.attributes().get("connectivity")));
            return Optional.of(new Sample(true, !offline, false, null, null, null, offline ? "OFFLINE" : "GOOD", "CONNECTIVITY_" + fact.attributes().get("connectivity"), 0));
        }
        var recorded = RecordedObservation.from(fact);
        if (recorded.isEmpty()) return Optional.empty();
        var evidence = evaluator.observeRecorded(condition, context.captured().scopeDeviceId(), context, recorded.get().twin(), recorded.get().inputs());
        return Optional.of(toSample(evidence));
    }

    private Sample sample(RuleEvaluationContext context, RuleCondition condition, UUID scope) {
        if (condition.temporal().operator() == TemporalOperator.OFFLINE_FOR) return new Sample(false, false, false, null, null, null, "N/A", null, 0);
        return toSample(evaluator.observe(condition, scope, context));
    }

    private Sample toSample(com.nexus.rule.domain.ConditionEvidence evidence) {
        boolean present = evidence.receivedAt() != null && !"MISSING".equals(String.valueOf(evidence.actualValue()));
        BigDecimal number = null;
        if (present && evidence.actualValue() instanceof Number n) number = new BigDecimal(n.toString());
        return new Sample(present, Boolean.TRUE.equals(evidence.eligible()), evidence.conditionMet(), number, evidence.receivedAt(), evidence.unit(),
            evidence.stateQuality() == null ? "UNKNOWN" : evidence.stateQuality(), evidence.qualityReason(),
            evidence.freshnessThresholdSeconds() == null ? 0 : evidence.freshnessThresholdSeconds());
    }

    private boolean relevant(RuleEvaluationSnapshot snapshot, RuleCondition condition, UUID scope, TriggerObservation fact) {
        var op = condition.temporal().operator();
        if (fact.type() == TriggerType.CONNECTIVITY_CHANGED)
            return op != TemporalOperator.ABSENCE_FOR; // quality loss resets positive progress; absence only follows accepted observations
        if (op == TemporalOperator.OFFLINE_FOR) return false;
        String identifier = condition.capabilityKey() != null ? condition.capabilityKey()
            : condition.capabilityType() == null ? null : com.nexus.device.capability.definition.LegacyCapabilityDefinitionMapping.keyFor(condition.capabilityType());
        if (identifier == null) return false;
        var capability = snapshot.capabilities().get(RuleEvaluationSnapshot.capabilityKey(scope, identifier, "READ"));
        Object factKey = fact.attributes().get("capabilityKey");
        return capability != null && factKey != null && capability.definitionKey().equalsIgnoreCase(factKey.toString());
    }

    private void applyFact(CapturedRule captured, RuleCondition condition, RuleEvaluationContext context, TriggerObservation fact, Instant now) {
        Rule rule = captured.rule();
        var identity = rule.executionIdentity();
        UUID scope = captured.scopeDeviceId();
        if (!relevant(context.snapshot(), condition, scope, fact)) return;
        timers.lockPending(identity.ruleVersionId(), condition.conditionId(), scope, identity.executionEpoch());   // timer row first, then state
        var state = store.lockOrCreate(rule.ruleId(), identity.ruleVersionId(), identity.ruleRevision(), condition.conditionId(), scope,
            identity.executionEpoch(), condition.temporal().operator().name(), now);
        String factKey = String.valueOf(fact.factId());
        if (factKey.equals(String.valueOf(state.watermark().get("lastFactId")))) return;   // each committed fact is applied once
        var spec = condition.temporal();
        var maybeSample = spec.operator() == TemporalOperator.ABSENCE_FOR || spec.operator() == TemporalOperator.OFFLINE_FOR
            ? Optional.of(new Sample(false, false, false, null, null, null, "N/A", null, 0)) : sampleOf(context, condition, scope, fact);
        if (maybeSample.isEmpty()) {
            // The observation cannot be reconstructed: continuity, count and trend evidence through it is unknowable. Fail closed.
            var watermark = new LinkedHashMap<>(state.watermark());
            watermark.put("lastFactId", factKey);
            var reset = reset(state, watermark, "OBSERVATION_NOT_RECONSTRUCTABLE");
            if ("ARMED".equals(state.phase())) timers.cancelPending(identity.ruleVersionId(), condition.conditionId(), scope, identity.executionEpoch(), "OBSERVATION_NOT_RECONSTRUCTABLE", now);
            store.save(reset, now);
            if ("ARMED".equals(state.phase())) store.evidence(reset, "RESET", now, Map.of("reason", "OBSERVATION_NOT_RECONSTRUCTABLE", "factId", factKey));
            return;
        }
        var sample = maybeSample.get();
        // A connectivity change can only invalidate positive progress. It never adds, extends or arms from a reading
        // that an earlier accepted observation already represents.
        if (fact.type() == TriggerType.CONNECTIVITY_CHANGED && spec.operator() != TemporalOperator.OFFLINE_FOR && sample.eligible()) {
            var watermark = new LinkedHashMap<>(state.watermark());
            watermark.put("lastFactId", factKey);
            store.save(copy(state, state.phase(), state.armedAt(), state.dueAt(), state.coverageEnd(), state.members(), watermark, state.sequence(), state.resetReason()), now);
            return;
        }
        switch (spec.operator()) {
            case FOR_DURATION -> forDuration(rule, identity, condition, scope, state, sample, fact, now);
            case COUNT_WITHIN -> countWithin(rule, identity, condition, scope, state, sample, fact, now);
            case TREND -> trend(rule, identity, condition, scope, state, sample, fact, now);
            case ABSENCE_FOR -> absenceObserved(rule, identity, condition, scope, state, sample, fact, now);
            case OFFLINE_FOR -> offlineObserved(rule, identity, condition, scope, state, fact, now);
        }
    }

    // ---------------------------------------------------------------- FOR_DURATION

    private void forDuration(Rule rule, RuleExecutionIdentity identity, RuleCondition condition, UUID scope, TemporalRuntimeEvidence state,
            Sample sample, TriggerObservation fact, Instant now) {
        long duration = condition.temporal().durationSeconds();
        var watermark = new LinkedHashMap<>(state.watermark());
        watermark.put("lastFactId", String.valueOf(fact.factId()));
        boolean qualified = sample.eligible() && sample.met() && sample.receivedAt() != null;
        if (!qualified && sample.receivedAt() != null && state.armedAt() != null && sample.receivedAt().isBefore(state.armedAt())) {
            // Disqualifying evidence that predates the current window belongs to a window that no longer exists.
            store.save(copy(state, state.phase(), state.armedAt(), state.dueAt(), state.coverageEnd(), state.members(), watermark, state.sequence(), state.resetReason()), now);
            store.evidence(state, "IGNORED_BEFORE_WINDOW", now, Map.of("factId", String.valueOf(fact.factId()), "receivedAt", sample.receivedAt().toString()));
            return;
        }
        if (!qualified) {
            if ("ARMED".equals(state.phase())) {
                String reason = !sample.present() ? "NO_VALUE" : !sample.eligible() ? "QUALITY_OR_UNIT_" + sample.quality() : "PREDICATE_FALSE";
                var reset = reset(state, watermark, reason);
                timers.cancelPending(identity.ruleVersionId(), condition.conditionId(), scope, identity.executionEpoch(), reason, now);
                store.save(reset, now);
                store.evidence(reset, "RESET", now, Map.of("reason", reason, "quality", sample.quality(), "factId", String.valueOf(fact.factId())));
            } else store.save(copy(state, state.phase(), state.armedAt(), state.dueAt(), state.coverageEnd(), state.members(), watermark, state.sequence(), state.resetReason()), now);
            return;
        }
        if (isLate(state, sample.receivedAt())) {
            store.save(copy(state, state.phase(), state.armedAt(), state.dueAt(), state.coverageEnd(), state.members(), watermark, state.sequence(), state.resetReason()), now);
            store.evidence(state, "IGNORED_LATE", now, Map.of("factId", String.valueOf(fact.factId()), "receivedAt", sample.receivedAt().toString()));
            return;
        }
        Instant coverageEnd = sample.receivedAt().plusSeconds(sample.threshold());
        watermark.put("lastReceivedAt", sample.receivedAt().toString());
        var member = member(fact, sample);
        if ("ARMED".equals(state.phase())) {
            if (state.coverageEnd() != null && state.coverageEnd().isBefore(sample.receivedAt())) {
                timers.cancelPending(identity.ruleVersionId(), condition.conditionId(), scope, identity.executionEpoch(), "FRESHNESS_GAP", now);
                store.evidence(state, "RESET", now, Map.of("reason", "FRESHNESS_GAP", "coverageEnd", state.coverageEnd().toString(), "nextReceivedAt", sample.receivedAt().toString()));
            } else {
                var members = bounded(state.members(), member);
                var extended = copy(state, "ARMED", state.armedAt(), state.dueAt(), later(state.coverageEnd(), coverageEnd), members, watermark, state.sequence(), null);
                store.save(extended, now);
                store.evidence(extended, "EXTENDED", now, Map.of("factId", String.valueOf(fact.factId()), "coverageEnd", extended.coverageEnd().toString()));
                return;
            }
        }
        Instant due = micro(sample.receivedAt().plusSeconds(duration));
        var armed = copy(state, "ARMED", sample.receivedAt(), due, coverageEnd, List.of(member), watermark, state.sequence(), null);
        timers.rescheduleOrArm(rule.ruleId(), identity.ruleVersionId(), identity.ruleRevision(), condition.conditionId(), scope, identity.executionEpoch(), due,
            Map.of("operator", "FOR_DURATION", "armedAt", sample.receivedAt().toString()), now);
        store.save(armed, now);
        store.evidence(armed, "ARMED", now, Map.of("factId", String.valueOf(fact.factId()), "armedAt", sample.receivedAt().toString(), "dueAt", due.toString(), "quality", sample.quality()));
    }

    // ---------------------------------------------------------------- COUNT_WITHIN

    private void countWithin(Rule rule, RuleExecutionIdentity identity, RuleCondition condition, UUID scope, TemporalRuntimeEvidence state,
            Sample sample, TriggerObservation fact, Instant now) {
        var spec = condition.temporal();
        var watermark = new LinkedHashMap<>(state.watermark());
        watermark.put("lastFactId", String.valueOf(fact.factId()));
        if (!sample.eligible()) {
            // Invalid or non-GOOD evidence resets positive progress; it never manufactures a false edge.
            if (!state.members().isEmpty()) {
                var reset = reset(state, watermark, "QUALITY_OR_UNIT_" + sample.quality());
                store.save(reset, now);
                store.evidence(reset, "RESET", now, Map.of("reason", "QUALITY_OR_UNIT_" + sample.quality(), "factId", String.valueOf(fact.factId())));
            } else store.save(copy(state, state.phase(), null, null, null, state.members(), watermark, state.sequence(), state.resetReason()), now);
            return;
        }
        if (!sample.met()) {
            if (spec.mode() == TemporalSpec.CountMode.EDGES) watermark.put("lastPredicate", false);
            store.save(copy(state, state.phase(), state.armedAt(), null, null, state.members(), watermark, state.sequence(), state.resetReason()), now);
            return;
        }
        if (spec.mode() == TemporalSpec.CountMode.EDGES && Boolean.TRUE.equals(watermark.get("lastPredicate"))) {
            store.save(copy(state, state.phase(), state.armedAt(), null, null, state.members(), watermark, state.sequence(), state.resetReason()), now);
            store.evidence(state, "NOT_AN_EDGE", now, Map.of("factId", String.valueOf(fact.factId())));
            return;
        }
        watermark.put("lastPredicate", true);
        if (!isLate(state, sample.receivedAt())) watermark.put("lastReceivedAt", sample.receivedAt().toString());   // late evidence still counts but never rewinds the watermark
        Instant windowStart = now.minusSeconds(spec.windowSeconds());   // window is (now - window, now]
        var members = new ArrayList<Map<String, Object>>();
        for (var existing : state.members()) {
            Instant at = Instant.parse(existing.get("receivedAt").toString());
            if (at.isAfter(windowStart)) members.add(existing);
        }
        boolean duplicate = members.stream().anyMatch(m -> String.valueOf(fact.factId()).equals(String.valueOf(m.get("factId"))));
        if (!duplicate) members.add(member(fact, sample));
        while (members.size() > spec.count()) members.removeFirst();
        boolean unknownIntermediate = members.size() >= spec.count()
            && hasUnappliedEvidence(scope, windowStart, now, String.valueOf(fact.factId()));
        if (unknownIntermediate) {
            // An earlier fact in this window is not applied yet: it could be non-GOOD evidence that must reset progress. Wait for it.
            var waiting = copy(state, "ARMED", Instant.parse(members.getFirst().get("receivedAt").toString()), null, null, members, watermark, state.sequence(), null);
            store.save(waiting, now);
            store.evidence(waiting, "COMPLETION_DEFERRED", now, Map.of("factId", String.valueOf(fact.factId()), "reason", "UNAPPLIED_EVIDENCE_IN_WINDOW"));
            return;
        }
        if (members.size() >= spec.count()) {
            var satisfied = copy(state, "SATISFIED", members.getFirst().get("receivedAt") == null ? now : Instant.parse(members.getFirst().get("receivedAt").toString()),
                now, null, List.of(), watermark, state.sequence() + 1, null);
            store.save(satisfied, now);
            store.evidence(satisfied, "SATISFIED", now, Map.of("operator", "COUNT_WITHIN", "countMode", spec.mode().name(), "members", members, "windowStart", windowStart.toString(), "evaluationTime", now.toString()));
            emit(rule, identity, condition, scope, satisfied, now);
        } else {
            var progress = copy(state, "ARMED", Instant.parse(members.getFirst().get("receivedAt").toString()), null, null, members, watermark, state.sequence(), null);
            store.save(progress, now);
            store.evidence(progress, duplicate ? "DUPLICATE_IGNORED" : "MEMBER_ADDED", now, Map.of("factId", String.valueOf(fact.factId()), "count", members.size()));
        }
    }

    // ---------------------------------------------------------------- TREND

    private void trend(Rule rule, RuleExecutionIdentity identity, RuleCondition condition, UUID scope, TemporalRuntimeEvidence state,
            Sample sample, TriggerObservation fact, Instant now) {
        var spec = condition.temporal();
        var watermark = new LinkedHashMap<>(state.watermark());
        watermark.put("lastFactId", String.valueOf(fact.factId()));
        if (!sample.eligible() || sample.number() == null || sample.receivedAt() == null) {
            if (!state.members().isEmpty()) {
                String reason = sample.number() == null && sample.eligible() ? "NON_NUMERIC" : "QUALITY_OR_UNIT_" + sample.quality();
                var reset = reset(state, watermark, reason);
                store.save(reset, now);
                store.evidence(reset, "RESET", now, Map.of("reason", reason, "factId", String.valueOf(fact.factId())));
            } else store.save(copy(state, state.phase(), null, null, null, state.members(), watermark, state.sequence(), state.resetReason()), now);
            return;
        }
        if (isLate(state, sample.receivedAt())) {
            // A sample that arrives behind newer evidence could be a dip inside the series; ordering is unknowable, so progress is invalid.
            var reset = reset(state, watermark, "OUT_OF_ORDER_SAMPLE");
            store.save(reset, now);
            if (!state.members().isEmpty()) store.evidence(reset, "RESET", now, Map.of("reason", "OUT_OF_ORDER_SAMPLE", "factId", String.valueOf(fact.factId())));
            return;
        }
        watermark.put("lastReceivedAt", sample.receivedAt().toString());
        Instant windowStart = now.minusSeconds(spec.windowSeconds());
        var members = new ArrayList<Map<String, Object>>();
        for (var existing : state.members()) {
            Instant at = Instant.parse(existing.get("receivedAt").toString());
            if (at.isAfter(windowStart)) members.add(existing);
        }
        if (!members.isEmpty() && !Objects.equals(String.valueOf(members.getFirst().get("unit")), String.valueOf(sample.unit()))) {
            store.evidence(state, "RESET", now, Map.of("reason", "UNIT_CHANGED"));
            members.clear();    // no silent conversion across units
        }
        if (members.stream().noneMatch(m -> String.valueOf(fact.factId()).equals(String.valueOf(m.get("factId"))))) members.add(member(fact, sample));
        while (members.size() > MAX_MEMBERS) members.removeFirst();
        int minSamples = Math.max(TemporalSpec.MIN_TREND_SAMPLES, spec.count() == null ? 0 : spec.count());
        boolean satisfied = false;
        Map<String, Object> details = new LinkedHashMap<>();
        if (members.size() >= minSamples) {
            Instant first = Instant.parse(members.getFirst().get("receivedAt").toString()), last = Instant.parse(members.getLast().get("receivedAt").toString());
            BigDecimal change = new BigDecimal(members.getLast().get("value").toString()).subtract(new BigDecimal(members.getFirst().get("value").toString()));
            boolean monotonic = true;
            for (int i = 1; i < members.size(); i++) {
                int cmp = new BigDecimal(members.get(i).get("value").toString()).compareTo(new BigDecimal(members.get(i - 1).get("value").toString()));
                if (spec.direction() == TemporalSpec.Direction.RISING ? cmp < 0 : cmp > 0) monotonic = false;
            }
            long span = Duration.between(first, last).getSeconds();
            boolean magnitude = spec.direction() == TemporalSpec.Direction.RISING ? change.compareTo(spec.minChange()) >= 0 : change.negate().compareTo(spec.minChange()) >= 0;
            satisfied = monotonic && magnitude && span >= spec.durationSeconds();
            details.put("samples", members.size()); details.put("spanSeconds", span); details.put("change", change.toPlainString());
            details.put("monotonic", monotonic); details.put("unit", String.valueOf(sample.unit()));
        }
        if (satisfied && hasUnappliedEvidence(scope, windowStart, now, String.valueOf(fact.factId()))) {
            var waiting = copy(state, "ARMED", Instant.parse(members.getFirst().get("receivedAt").toString()), null, null, members, watermark, state.sequence(), null);
            store.save(waiting, now);
            store.evidence(waiting, "COMPLETION_DEFERRED", now, Map.of("factId", String.valueOf(fact.factId()), "reason", "UNAPPLIED_EVIDENCE_IN_WINDOW"));
            return;
        }
        if (satisfied) {
            details.put("operator", "TREND"); details.put("members", members); details.put("evaluationTime", now.toString());
            var done = copy(state, "SATISFIED", Instant.parse(members.getFirst().get("receivedAt").toString()), now, null, List.of(), watermark, state.sequence() + 1, null);
            store.save(done, now);
            store.evidence(done, "SATISFIED", now, details);
            emit(rule, identity, condition, scope, done, now);
        } else {
            var progress = copy(state, "ARMED", Instant.parse(members.getFirst().get("receivedAt").toString()), null, null, members, watermark, state.sequence(), null);
            store.save(progress, now);
            store.evidence(progress, "MEMBER_ADDED", now, Map.of("factId", String.valueOf(fact.factId()), "samples", members.size()));
        }
    }

    // ---------------------------------------------------------------- ABSENCE_FOR / OFFLINE_FOR

    private void absenceObserved(Rule rule, RuleExecutionIdentity identity, RuleCondition condition, UUID scope, TemporalRuntimeEvidence state,
            Sample sample, TriggerObservation fact, Instant now) {
        if (fact.type() != TriggerType.ACCEPTED_TELEMETRY) return;
        var watermark = new LinkedHashMap<>(state.watermark());
        watermark.put("lastFactId", String.valueOf(fact.factId()));
        Instant factReceived = factReceivedAt(fact);
        Instant observed = factReceived != null ? factReceived : fact.recordedAt();
        if (state.armedAt() != null && !observed.isAfter(state.armedAt()) && "ARMED".equals(state.phase())) {
            store.save(copy(state, state.phase(), state.armedAt(), state.dueAt(), null, state.members(), watermark, state.sequence(), state.resetReason()), now);
            return;     // not newer than the current arm point: does not rearm
        }
        Instant due = micro(observed.plusSeconds(condition.temporal().durationSeconds()));
        watermark.put("lastObservationAt", observed.toString());
        var rearmed = copy(state, "ARMED", observed, due, null, List.of(), watermark, state.sequence(), null);
        timers.rescheduleOrArm(rule.ruleId(), identity.ruleVersionId(), identity.ruleRevision(), condition.conditionId(), scope, identity.executionEpoch(), due,
            Map.of("operator", "ABSENCE_FOR", "armedAt", observed.toString()), now);
        store.save(rearmed, now);
        store.evidence(rearmed, "REARMED", now, Map.of("factId", String.valueOf(fact.factId()), "observationAt", observed.toString(), "dueAt", due.toString()));
    }

    private void offlineObserved(Rule rule, RuleExecutionIdentity identity, RuleCondition condition, UUID scope, TemporalRuntimeEvidence state,
            TriggerObservation fact, Instant now) {
        if (fact.type() != TriggerType.CONNECTIVITY_CHANGED) return;
        var watermark = new LinkedHashMap<>(state.watermark());
        watermark.put("lastFactId", String.valueOf(fact.factId()));
        String status = String.valueOf(fact.attributes().get("connectivity"));
        if (DeviceConnectivityStatus.OFFLINE.name().equals(status)) {
            Instant activated = watermark.get("activatedAt") == null ? now : Instant.parse(watermark.get("activatedAt").toString());
            Instant armedAt = fact.occurredAt().isAfter(activated) ? fact.occurredAt() : activated;     // never earlier than activation/resume
            Instant due = micro(armedAt.plusSeconds(condition.temporal().durationSeconds()));
            watermark.put("offlineSince", micro(fact.occurredAt()).toString());
            var armed = copy(state, "ARMED", armedAt, due, null, List.of(), watermark, state.sequence(), null);
            timers.rescheduleOrArm(rule.ruleId(), identity.ruleVersionId(), identity.ruleRevision(), condition.conditionId(), scope, identity.executionEpoch(), due,
                Map.of("operator", "OFFLINE_FOR", "offlineSince", watermark.get("offlineSince")), now);
            store.save(armed, now);
            store.evidence(armed, "ARMED", now, Map.of("factId", String.valueOf(fact.factId()), "offlineSince", watermark.get("offlineSince"), "armedAt", armedAt.toString(), "dueAt", due.toString()));
        } else {
            watermark.remove("offlineSince");
            var reset = reset(state, watermark, "CONNECTIVITY_" + status);
            timers.cancelPending(identity.ruleVersionId(), condition.conditionId(), scope, identity.executionEpoch(), "CONNECTIVITY_" + status, now);
            store.save(reset, now);
            if ("ARMED".equals(state.phase())) store.evidence(reset, "RESET", now, Map.of("reason", "CONNECTIVITY_" + status, "factId", String.valueOf(fact.factId())));
        }
    }

    // ---------------------------------------------------------------- due processing (inside the worker transaction)

    /** Rechecks ownership-independent evidence at expiry and either emits the TEMPORAL_DUE fact or records the reset. */
    public void consumeDue(Map<String, Object> timer, Instant now) {
        UUID timerId = (UUID) timer.get("timer_id"), rule = (UUID) timer.get("rule_id"), version = (UUID) timer.get("rule_version_id");
        UUID scope = (UUID) timer.get("scope_device_id");
        String conditionId = (String) timer.get("condition_id");
        long generation = ((Number) timer.get("generation")).longValue();
        Instant timerDue = ((java.sql.Timestamp) timer.get("due_at")).toInstant();
        var synthetic = new TriggerObservation(TriggerType.TEMPORAL_DUE, timerId, null, null, scope, timerDue, now, Map.of(), Map.of(), "timer:" + timerId, null, 0, List.of());
        RuleEvaluationSnapshot snapshot = snapshots.acquireTemporal(synthetic, rule, now);
        CapturedRule captured = snapshot.rules().stream().filter(c -> c.rule().ruleId().equals(rule)).findFirst().orElse(null);
        if (captured == null || !captured.executable() || captured.rule().executionIdentity() == null
                || !version.equals(captured.rule().executionIdentity().ruleVersionId()) || generation != captured.rule().executionIdentity().executionEpoch()) {
            timers.finish(timerId, null, "NOT_ELIGIBLE_AT_DUE", now);
            return;
        }
        RuleCondition condition = captured.rule().conditions().stream().filter(c -> c.temporal() != null && conditionId.equals(c.conditionId())).findFirst().orElse(null);
        var identity = captured.rule().executionIdentity();
        var state = condition == null ? null : store.lockOrCreate(rule, version, identity.ruleRevision(), conditionId, scope, generation, condition.temporal().operator().name(), now);
        if (state == null || !"ARMED".equals(state.phase()) || state.dueAt() == null || !state.dueAt().equals(micro(timerDue))) {
            timers.finish(timerId, null, "STALE_RUNTIME_OCCURRENCE", now);
            return;
        }
        var context = new RuleEvaluationContext(UUID.randomUUID(), snapshot, captured, synthetic);
        Instant gateFrom = state.armedAt() == null ? now : state.armedAt();
        if (hasUnappliedEvidence(scope, gateFrom, now, null)) {
            // Evidence recorded since the window opened has not been applied (in flight, failed or awaiting recovery): the interval is
            // not fully known, so it cannot be completed yet. Retry shortly; a quarantined fact resets the state instead.
            timers.defer(timerId, now.plusSeconds(5));
            store.evidence(state, "COMPLETION_DEFERRED", now, Map.of("reason", "UNAPPLIED_EVIDENCE_SINCE_ARM", "dueAt", String.valueOf(state.dueAt())));
            return;
        }
        switch (condition.temporal().operator()) {
            case FOR_DURATION -> dueForDuration(captured.rule(), identity, condition, scope, state, context, timerId, now);
            case ABSENCE_FOR -> dueAbsence(captured.rule(), identity, condition, scope, state, context, snapshot, timerId, now);
            case OFFLINE_FOR -> dueOffline(captured.rule(), identity, condition, scope, state, snapshot, timerId, now);
            default -> { timers.finish(timerId, null, "NO_TIMER_FOR_OPERATOR", now); }
        }
    }

    private void dueForDuration(Rule rule, RuleExecutionIdentity identity, RuleCondition condition, UUID scope, TemporalRuntimeEvidence state,
            RuleEvaluationContext context, UUID timerId, Instant now) {
        var sample = sample(context, condition, scope);
        boolean coverage = state.coverageEnd() != null && !state.coverageEnd().isBefore(state.dueAt());
        boolean qualifiedNow = sample.eligible() && sample.met();
        if (!now.isBefore(state.dueAt()) && coverage && qualifiedNow) {
            var done = copy(state, "SATISFIED", state.armedAt(), state.dueAt(), state.coverageEnd(), state.members(), state.watermark(), state.sequence() + 1, null);
            store.save(done, now);
            store.evidence(done, "SATISFIED", now, Map.of("operator", "FOR_DURATION", "armedAt", String.valueOf(state.armedAt()), "dueAt", state.dueAt().toString(),
                "coverageEnd", String.valueOf(state.coverageEnd()), "members", state.members(), "quality", sample.quality(), "evaluationTime", now.toString()));
            UUID fact = emit(rule, identity, condition, scope, done, state.dueAt());
            timers.finish(timerId, fact, null, now);
        } else {
            String reason = !coverage ? "FRESHNESS_GAP_AT_DUE" : "NOT_QUALIFIED_AT_DUE";
            var reset = reset(state, state.watermark(), reason);
            store.save(reset, now);
            store.evidence(reset, "RESET", now, Map.of("reason", reason, "quality", sample.quality(), "dueAt", state.dueAt().toString(), "coverageEnd", String.valueOf(state.coverageEnd())));
            timers.finish(timerId, null, reason, now);
        }
    }

    private void dueAbsence(Rule rule, RuleExecutionIdentity identity, RuleCondition condition, UUID scope, TemporalRuntimeEvidence state,
            RuleEvaluationContext context, RuleEvaluationSnapshot snapshot, UUID timerId, Instant now) {
        var sample = sample(context, condition, scope);
        Instant newest = sample.receivedAt();
        if (newest != null && newest.isAfter(state.armedAt())) {
            // A newer accepted observation committed after arming: re-read the watermark and rearm instead of completing.
            Instant due = micro(newest.plusSeconds(condition.temporal().durationSeconds()));
            var rearmed = copy(state, "ARMED", newest, due, null, List.of(), state.watermark(), state.sequence(), null);
            timers.rescheduleOrArm(rule.ruleId(), identity.ruleVersionId(), identity.ruleRevision(), condition.conditionId(), scope, identity.executionEpoch(), due, Map.of("operator", "ABSENCE_FOR"), now);
            store.save(rearmed, now);
            store.evidence(rearmed, "REARMED_AT_DUE", now, Map.of("newestObservationAt", newest.toString(), "dueAt", due.toString()));
            return;
        }
        var done = copy(state, "SATISFIED", state.armedAt(), state.dueAt(), null, List.of(), state.watermark(), state.sequence() + 1, null);
        store.save(done, now);
        // Missing observations are an explicit evidence exception, never a fabricated GOOD sample.
        store.evidence(done, "SATISFIED", now, Map.of("operator", "ABSENCE_FOR", "armedAt", String.valueOf(state.armedAt()), "dueAt", state.dueAt().toString(),
            "sourceWatermark", String.valueOf(newest), "quality", "NO_OBSERVATION_SINCE_ARM", "evaluationTime", now.toString()));
        UUID fact = emit(rule, identity, condition, scope, done, state.dueAt());
        timers.finish(timerId, fact, null, now);
    }

    private void dueOffline(Rule rule, RuleExecutionIdentity identity, RuleCondition condition, UUID scope, TemporalRuntimeEvidence state,
            RuleEvaluationSnapshot snapshot, UUID timerId, Instant now) {
        var link = snapshot.connectivity().get(scope);
        String expectedSince = String.valueOf(state.watermark().get("offlineSince"));
        boolean stillOffline = link != null && DeviceConnectivityStatus.OFFLINE.name().equals(link.status())
            && micro(link.lastChangedAt()).toString().equals(expectedSince);
        if (!stillOffline || now.isBefore(state.dueAt())) {
            String reason = link == null ? "CONNECTIVITY_UNKNOWN" : "CONNECTIVITY_CHANGED_BEFORE_DUE";
            var reset = reset(state, state.watermark(), reason);
            store.save(reset, now);
            store.evidence(reset, "RESET", now, Map.of("reason", reason, "observed", link == null ? "UNKNOWN" : link.status()));
            timers.finish(timerId, null, reason, now);
            return;
        }
        var done = copy(state, "SATISFIED", state.armedAt(), state.dueAt(), null, List.of(), state.watermark(), state.sequence() + 1, null);
        store.save(done, now);
        store.evidence(done, "SATISFIED", now, Map.of("operator", "OFFLINE_FOR", "offlineSince", expectedSince, "armedAt", String.valueOf(state.armedAt()),
            "dueAt", state.dueAt().toString(), "observed", link.status(), "evaluationTime", now.toString(),
            "meaning", "persisted connectivity evidence, not electrical or physical truth"));
        UUID fact = emit(rule, identity, condition, scope, done, state.dueAt());
        timers.finish(timerId, fact, null, now);
    }

    // ---------------------------------------------------------------- helpers

    /** Emits the TEMPORAL_DUE source fact in the caller's transaction; the key is the runtime occurrence identity. */
    private UUID emit(Rule rule, RuleExecutionIdentity identity, RuleCondition condition, UUID scope, TemporalRuntimeEvidence state, Instant at) {
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("ruleId", rule.ruleId().toString()); attributes.put("ruleVersionId", identity.ruleVersionId().toString());
        attributes.put("conditionId", condition.conditionId()); attributes.put("sequence", state.sequence());
        attributes.put("generation", state.generation()); attributes.put("operator", condition.temporal().operator().name());
        String key = identity.ruleVersionId() + "|" + condition.conditionId() + "|" + scope + "|" + state.generation() + "|" + state.sequence();
        return recorder.recordAndRoute(new RuleTriggerFactRecorder.Fact(TriggerType.TEMPORAL_DUE, key, scope, at,
            Map.of("operator", condition.temporal().operator().name(), "sequence", state.sequence()), attributes, null)).orElse(null);
    }

    /** Backend receipt time of the Twin row this accepted-telemetry fact produced, microsecond precision like the database. */
    private static Instant factReceivedAt(TriggerObservation fact) {
        Object raw = fact.sourceRef().get("receivedAt");
        if (raw == null || "null".equals(raw.toString())) return null;
        try { return micro(Instant.parse(raw.toString())); } catch (RuntimeException e) { return null; }
    }

    private static boolean isLate(TemporalRuntimeEvidence state, Instant receivedAt) {
        Object last = state.watermark().get("lastReceivedAt");
        return last != null && receivedAt.isBefore(Instant.parse(last.toString()));    // never rewinds runtime progress
    }

    private static Map<String, Object> member(TriggerObservation fact, Sample sample) {
        var member = new LinkedHashMap<String, Object>();
        member.put("factId", String.valueOf(fact.factId())); member.put("receivedAt", sample.receivedAt().toString());
        if (sample.number() != null) member.put("value", sample.number().toPlainString());
        member.put("unit", String.valueOf(sample.unit())); member.put("quality", sample.quality());
        return member;
    }

    private static List<Map<String, Object>> bounded(List<Map<String, Object>> existing, Map<String, Object> member) {
        var members = new ArrayList<>(existing);
        members.add(member);
        while (members.size() > 50) members.remove(1);      // keep the start evidence plus the most recent coverage
        return members;
    }

    /** PostgreSQL stores timestamptz with microsecond precision and rounds; Instant.now() carries 100 ns. Compare only rounded values. */
    static Instant micro(Instant at) {
        if (at == null) return null;
        long nanos = (at.getNano() + 500L) / 1000L * 1000L;
        return Instant.ofEpochSecond(at.getEpochSecond(), 0).plusNanos(nanos);
    }

    private static Instant later(Instant a, Instant b) { return a == null ? b : a.isAfter(b) ? a : b; }

    private static TemporalRuntimeEvidence reset(TemporalRuntimeEvidence s, Map<String, Object> watermark, String reason) {
        return copy(s, "IDLE", null, null, null, List.of(), watermark, s.sequence(), reason);
    }

    private static TemporalRuntimeEvidence copy(TemporalRuntimeEvidence s, String phase, Instant armedAt, Instant dueAt, Instant coverageEnd,
            List<Map<String, Object>> members, Map<String, Object> watermark, long sequence, String reason) {
        return new TemporalRuntimeEvidence(s.ruleId(), s.ruleVersionId(), s.ruleRevision(), s.conditionId(), s.scopeDeviceId(), s.generation(), s.operator(),
            phase, armedAt, micro(dueAt), micro(coverageEnd), members, watermark, sequence, reason, s.updatedAt());
    }
}
