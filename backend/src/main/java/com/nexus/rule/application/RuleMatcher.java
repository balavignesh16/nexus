package com.nexus.rule.application;

import com.nexus.event.application.EventDispatcher;
import com.nexus.event.application.EventListener;
import com.nexus.event.domain.DomainEvent;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.rule.domain.RuleMatchedEvent;
import com.nexus.rule.persistence.BoundedRuleMatchStore;
import com.nexus.rule.persistence.RuleMatchPersister;
import com.nexus.rule.persistence.RuleMatchRepository;
import com.nexus.rule.persistence.RuleRegistry;
import com.nexus.rule.snapshot.CapturedRule;
import com.nexus.rule.trigger.TriggerObservation;
import com.nexus.rule.snapshot.RuleEvaluationContext;
import com.nexus.rule.snapshot.RuleEvaluationSnapshot;
import com.nexus.realtime.RealtimePublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RuleMatcher implements EventListener {
    private java.time.Clock clock=java.time.Clock.systemUTC();
    @org.springframework.beans.factory.annotation.Autowired
    void setClock(java.time.Clock clock){this.clock=clock;}
    private com.nexus.event.persistence.DomainEventPersister durableSources;
    private RuleExecutionAuthorization authorization;
    private RuleCapabilityValidator runtimeValidator;
    @org.springframework.beans.factory.annotation.Autowired
    void setRuntimeValidator(RuleCapabilityValidator validator){this.runtimeValidator=validator;}
    @org.springframework.beans.factory.annotation.Autowired
    void setFoundation(com.nexus.event.persistence.DomainEventPersister durableSources,RuleExecutionAuthorization authorization){this.durableSources=durableSources;this.authorization=authorization;}

    private com.nexus.rule.plan.RuleActionPlanner actionPlanner;
    @org.springframework.beans.factory.annotation.Autowired
    void setActionPlanner(@org.springframework.context.annotation.Lazy com.nexus.rule.plan.RuleActionPlanner actionPlanner){this.actionPlanner=actionPlanner;}
    private com.nexus.rule.persistence.RuleRepeatStateStore repeatState;
    @org.springframework.beans.factory.annotation.Autowired
    void setRepeatState(com.nexus.rule.persistence.RuleRepeatStateStore repeatState){this.repeatState=repeatState;}
    private com.nexus.rule.persistence.RuleHysteresisStore hysteresisStore;
    @org.springframework.beans.factory.annotation.Autowired
    void setHysteresisStore(com.nexus.rule.persistence.RuleHysteresisStore store){this.hysteresisStore=store;}
    private RuleEvaluationSnapshotService snapshots;
    @org.springframework.beans.factory.annotation.Autowired
    void setSnapshots(RuleEvaluationSnapshotService snapshots){this.snapshots=snapshots;}

    private static final Logger log = LoggerFactory.getLogger(RuleMatcher.class);

    private final EventDispatcher eventDispatcher;
    private final RuleRegistry ruleRegistry;
    private final RuleEvaluator ruleEvaluator;
    private final BoundedRuleMatchStore matchStore;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final RealtimePublisher realtimePublisher;
    private final RuleMatchPersister ruleMatchPersister;
    private final RuleMatchRepository ruleMatchRepository;

    // Phase 13 cooldown. Per-rule Instant of the last durably-persisted
    // match, hydrated once at startup from rule_matches (the durable source
    // of truth) - never written to before a match's persist() call has
    // already succeeded, so a persistence failure can never poison cooldown
    // state (see the per-rule lock in onEvent below).
    // A cache only: the authority is rule_cooldown_state, locked and re-checked inside the match transaction (cross-instance safe).
    // Keyed by logical rule + cooldown scope key; a miss or a stale entry can only lead to the authoritative database check.
    private final ConcurrentHashMap<String, Instant> lastMatchedAt = new ConcurrentHashMap<>();
    private static String cooldownKey(UUID ruleId, String scopeKey) { return ruleId + "|" + scopeKey; }
    // One intrinsic lock object per rule - serializes concurrent events for
    // the SAME rule around its own check-evaluate-persist-commit sequence,
    // without blocking evaluation of any other rule.
    private final ConcurrentHashMap<UUID, Object> ruleLocks = new ConcurrentHashMap<>();

    public RuleMatcher(EventDispatcher eventDispatcher, RuleRegistry ruleRegistry, RuleEvaluator ruleEvaluator,
                        BoundedRuleMatchStore matchStore, ApplicationEventPublisher applicationEventPublisher,
                        RealtimePublisher realtimePublisher, RuleMatchPersister ruleMatchPersister,
                        RuleMatchRepository ruleMatchRepository) {
        this.eventDispatcher = eventDispatcher;
        this.ruleRegistry = ruleRegistry;
        this.ruleEvaluator = ruleEvaluator;
        this.matchStore = matchStore;
        this.applicationEventPublisher = applicationEventPublisher;
        this.realtimePublisher = realtimePublisher;
        this.ruleMatchPersister = ruleMatchPersister;
        this.ruleMatchRepository = ruleMatchRepository;
    }

    @PostConstruct
    public void init() {
        eventDispatcher.subscribe(this);
        hydrateCooldownState();
    }

    /**
     * Rebuilds "last matched at" per rule from the durable rule_matches
     * table (one aggregate query, at startup only) - restart-correct by
     * construction: a match that was never durably persisted (e.g. a
     * transient failure) never contributed a row here, so it correctly
     * never contributes to cooldown either, before or after a restart.
     */
    private void hydrateCooldownState() {
        for (Object[] row : ruleMatchRepository.findLastMatchedAtByRule()) {
            UUID ruleId = (UUID) row[0];
            Instant matchedAt = (Instant) row[1];
            lastMatchedAt.put(cooldownKey(ruleId, ""), matchedAt);
        }
    }

    @PreDestroy
    public void cleanup() {
        eventDispatcher.unregister(this);
    }

    @Override
    public void onEvent(DomainEvent event) {
        if(durableSources!=null) {
            if(!java.util.Set.of(com.nexus.event.domain.EventType.TWIN_CREATED,com.nexus.event.domain.EventType.TWIN_UPDATED,com.nexus.event.domain.EventType.SENSOR_VALUE_CHANGED).contains(event.eventType()))return;
            try { event=durableSources.persistRequired(event); }
            catch(RuntimeException failure) { log.error("RULE_SOURCE_PERSISTENCE_SUPPRESSED {}",event.eventId(),failure);return; }
        }
        evaluateBatch(TriggerObservation.legacy(event), rule -> true);
    }

    /**
     * Entry for a committed typed source fact, loaded by RuleTriggerRouter after the source transaction committed.
     * Every match - typed or legacy - has its actions decided and authorized by RuleActionPlanner.
     */
    public java.util.Optional<String> processFact(TriggerObservation observation) { return processFact(observation, rule -> true); }

    /** @param admitted which active rules may evaluate this fact (recovery admits only the authorities recorded with the fact). */
    /** @return the supersession reason when the fact's source was already replaced in the evaluation snapshot (nothing was evaluated); empty otherwise. */
    public java.util.Optional<String> processFact(TriggerObservation observation, java.util.function.Predicate<Rule> admitted) {
        if (!observation.typed() || snapshots == null)
            throw new IllegalStateException("Typed triggers require the frozen-context snapshot service");
        return evaluateBatch(observation, admitted);
    }

    private java.util.Optional<String> evaluateBatch(TriggerObservation trigger, java.util.function.Predicate<Rule> admitted) {
        DomainEvent event = trigger.legacyEvent();
        // Production path: one REPEATABLE READ snapshot (through the Spring proxy) serves the whole batch.
        // The transaction has ended before any rule lock, match write or effect below.
        RuleEvaluationSnapshot snapshot = null;
        Map<UUID, CapturedRule> capturedByRule = Map.of();
        List<Rule> activeRules;
        if (snapshots != null) {
            try {
                snapshot = snapshots.acquire(trigger, clock.instant());
            } catch (RuntimeException failure) {
                log.error("RULE_SNAPSHOT_FAILED_CLOSED {}", trigger.sourceId(), failure);
                throw failure; // same outage semantics as the prior registry read: nothing is evaluated or dispatched
            }
            if (snapshot.triggerSuperseded().isPresent()) {
                // The trigger's source was replaced by a newer one before this snapshot: no rule is evaluated, no match or plan is written.
                log.info("RULE_TRIGGER_SUPERSEDED {} {}", trigger.sourceId(), snapshot.triggerSuperseded().get());
                return snapshot.triggerSuperseded();
            }
            var byRule = new java.util.LinkedHashMap<UUID, CapturedRule>();
            for (CapturedRule captured : snapshot.rules()) byRule.put(captured.rule().ruleId(), captured);
            capturedByRule = byRule;
            activeRules = snapshot.rules().stream().map(CapturedRule::rule).toList();
        } else {
            activeRules = ruleRegistry.listEnabledOrderedByPriority();
        }
        final RuleEvaluationSnapshot frozenSnapshot = snapshot;
        var typedMatches = new java.util.ArrayList<RuleMatchedEvent>();
        var legacyMatches = new java.util.ArrayList<RuleMatchedEvent>();
        for (Rule rule : activeRules) {
            if (!admitted.test(rule)) continue;
            // A logical rule may not repeat in its own causation chain, regardless of version changes (C17).
            if (trigger.causationRuleIds().contains(rule.ruleId())) {
                log.warn("RULE_CHAIN_REJECTED rule={} trigger={} reason=CYCLE", rule.ruleId(), trigger.sourceId());
                ruleMatchPersister.recordChainRejection(trigger, rule.ruleId(), "CYCLE");
                continue;
            }
            Object lock = ruleLocks.computeIfAbsent(rule.ruleId(), id -> new Object());
            synchronized (lock) {
                try {
                    CapturedRule frozen = capturedByRule.get(rule.ruleId());
                    if (trigger.typed() && frozen != null && rule.executionIdentity() != null) {
                        // Re-routing a recovered fact: a decision that already exists is reused, never duplicated, so a match whose
                        // plan was never persisted can still obtain its (single) plan.
                        var existing = ruleMatchPersister.existingMatch(rule.ruleId(), rule.executionIdentity().ruleVersionId(), trigger.factId());
                        if (existing != null && existing.isPresent()) {
                            typedMatches.add(new RuleMatchedEvent(existing.get(), clock.instant(), new RuleEvaluationResult(true, rule, null,
                                "Recovered persisted match", List.of(), new RuleEvaluationContext(UUID.randomUUID(), frozenSnapshot, frozen, trigger)), trigger.correlationId()));
                            continue;
                        }
                    }
                    String scopeKey = com.nexus.rule.persistence.RuleCooldownStore.scopeKey(rule, frozen == null ? null : frozen.scopeDeviceId());
                    if (!trigger.typed() && frozen != null && rule.executionIdentity() != null) {
                        // A redelivered legacy event reuses the match already persisted for this rule version (no duplicate history, no second cooldown claim).
                        var existingLegacy = ruleMatchPersister.existingLegacyMatch(rule.ruleId(), rule.executionIdentity().ruleVersionId(), trigger.sourceId());
                        if (existingLegacy != null && existingLegacy.isPresent()) {
                            legacyMatches.add(new RuleMatchedEvent(existingLegacy.get(), clock.instant(), new RuleEvaluationResult(true, rule, trigger.legacyEvent(), "Recovered persisted match", List.of(),
                                new RuleEvaluationContext(UUID.randomUUID(), frozenSnapshot, frozen, trigger)), trigger.correlationId()));
                            continue;
                        }
                    }
                    // A plain rule skips evaluation inside its cooldown window (unchanged). A rule that keeps latch state (EDGE_TRUE, hysteresis)
                    // is still evaluated so its latches follow the truth, and only the MATCH is suppressed below.
                    boolean stateful = rule.repeat() == com.nexus.rule.domain.RepeatPolicy.EDGE_TRUE
                        || (rule.conditions() != null && rule.conditions().stream().anyMatch(c -> c != null && c.hysteresis() != null));
                    boolean cooling = isInCooldown(rule, scopeKey);
                    if (cooling && !stateful) {
                        continue;
                    }
                    if (frozen != null) {
                        if (frozen.scopeMissing()) { log.warn("RULE_SCOPE_SUPPRESSED {}", rule.ruleId()); continue; }
                        if (!frozen.diagnostics().isEmpty()) { log.warn("RULE_RUNTIME_CONTRACT_SUPPRESSED {} {}", rule.ruleId(), frozen.diagnostics()); continue; }
                    } else if(runtimeValidator!=null) {
                        Rule validationRule=rule;
                        boolean explicit=rule.conditions().stream().anyMatch(c->c.field()==com.nexus.rule.domain.RuleField.DEVICE_ID);
                        if(!explicit) {
                            if(event.deviceId()==null){log.warn("RULE_SCOPE_SUPPRESSED {}",rule.ruleId());continue;}
                            var conditions=new java.util.ArrayList<>(rule.conditions());
                            conditions.add(new com.nexus.rule.domain.RuleCondition(com.nexus.rule.domain.RuleField.DEVICE_ID,com.nexus.rule.domain.RuleOperator.EQ,event.deviceId().toString()));
                            validationRule=new Rule(rule.ruleId(),rule.name(),rule.enabled(),rule.priority(),conditions,rule.actions(),rule.metadata(),rule.cooldownSeconds(),rule.executionIdentity(),rule.runtime());
                        }
                        var diagnostics=runtimeValidator.inspect(validationRule);
                        if(!diagnostics.isEmpty()){log.warn("RULE_RUNTIME_CONTRACT_SUPPRESSED {} {}",rule.ruleId(),diagnostics);continue;}
                    }

                    RuleEvaluationResult result = frozen != null
                            ? ruleEvaluator.evaluate(rule, event, new RuleEvaluationContext(UUID.randomUUID(), frozenSnapshot, frozen, trigger))
                            : ruleEvaluator.evaluate(rule, event);
                    // Hysteresis latches follow DEFINITE ENTER/EXIT transitions only, stamped with the observation time (an older observation
                    // from another instance never overwrites a newer one). Short-circuited conditions have no evidence, so they change nothing.
                    if (hysteresisStore != null && frozen != null && frozen.scopeDeviceId() != null && rule.executionIdentity() != null) {
                        for (var conditionEvidence : result.evidence()) {
                            var band = conditionEvidence.hysteresis();
                            if (band != null && band.transition() && band.conditionId() != null)
                                hysteresisStore.apply(rule.ruleId(), band.conditionId(), frozen.scopeDeviceId(), rule.executionIdentity().executionEpoch(),
                                    band.latchedAfter(), trigger.recordedAt());
                        }
                    }
                    if (rule.repeat() == com.nexus.rule.domain.RepeatPolicy.EDGE_TRUE && repeatState != null && frozen != null
                            && frozen.scopeDeviceId() != null && rule.executionIdentity() != null) {
                        long generation = rule.executionIdentity().executionEpoch();
                        if (result.matched() && repeatState.latched(rule.ruleId(), frozen.scopeDeviceId(), generation)) {
                            log.info("RULE_EDGE_SUPPRESSED rule={} trigger={} (still true since the last edge)", rule.ruleId(), trigger.sourceId());
                            continue;
                        }
                        // Only definite false evidence rearms the edge; UNKNOWN, stale or invalid evidence never manufactures one.
                        // The rearm carries its observation time, so a slower instance cannot undo a newer consumed edge.
                        if (!result.matched() && definitelyFalse(result)) repeatState.rearm(rule.ruleId(), frozen.scopeDeviceId(), generation, trigger.recordedAt());
                    }
                    if (result.matched() && cooling) {
                        log.info("RULE_MATCH_SUPPRESSED rule={} trigger={} reason=COOLDOWN", rule.ruleId(), trigger.sourceId());
                        continue;
                    }
                    if (result.matched()) {
                        log.info("Rule matched! Rule: '{}', Trigger: {}", rule.name(), trigger.type());
                        RuleMatchedEvent matchedEvent = new RuleMatchedEvent(
                                UUID.randomUUID(),
                                clock.instant(),
                                result,
                                trigger.correlationId()
                        );

                        // Persist first - only once this line returns
                        // successfully has the match been durably recorded.
                        // Everything below, including the cooldown-timestamp
                        // update, is unreachable if persist() throws, so a
                        // persistence failure can never create a cooldown
                        // window for a match that was never actually recorded.
                        boolean cooldownEligible;
                        try { cooldownEligible=ruleMatchPersister.persist(matchedEvent); }
                        catch (com.nexus.rule.persistence.RuleMatchSuppressedException suppressed) {
                            // The durable gate (another instance, or an earlier match) refused: nothing was written.
                            if (suppressed.lastMatchedAt() != null) lastMatchedAt.merge(cooldownKey(rule.ruleId(), scopeKey), suppressed.lastMatchedAt(), (a, b) -> a.isAfter(b) ? a : b);
                            log.info("RULE_MATCH_SUPPRESSED rule={} trigger={} reason={}", rule.ruleId(), trigger.sourceId(), suppressed.reason());
                            continue;
                        }

                        // Preserve eligibility decided inside the committed match transaction.
                        // A later owner change cannot erase a legitimate logical-rule cooldown.
                        if (rule.cooldownSeconds() != null && (authorization==null || cooldownEligible)) {
                            lastMatchedAt.put(cooldownKey(rule.ruleId(), scopeKey), matchedEvent.timestamp());
                        }

                        matchStore.add(matchedEvent);
                        realtimePublisher.publishRuleMatch(result, trigger.correlationId());
                        if (trigger.typed()) typedMatches.add(matchedEvent);
                        else { applicationEventPublisher.publishEvent(matchedEvent); legacyMatches.add(matchedEvent); }
                    }
                } catch (Exception e) {
                    log.error("Failed to evaluate rule {} against trigger {}", rule.ruleId(), trigger.sourceId(), e);
                }
            }
        }
        if (trigger.typed() && !typedMatches.isEmpty()) actionPlanner.planAndDispatch(trigger.factId(), typedMatches);
        // Legacy DomainEvent matches take the SAME persisted-arbitration pathway (the C13 exception is retired): one plan per event.
        if (!trigger.typed() && !legacyMatches.isEmpty() && actionPlanner != null)
            actionPlanner.planAndDispatch(com.nexus.rule.plan.PlanSource.event(trigger.sourceId()), legacyMatches);
        return java.util.Optional.empty();
    }

    private static boolean definitelyFalse(RuleEvaluationResult result) {
        if (result.evidence().isEmpty()) return false;
        // The failing condition is the FIRST non-met entry (hysteresis tracking evidence may follow it).
        var failing = result.evidence().stream().filter(e -> !e.conditionMet()).findFirst().orElse(null);
        if (failing == null) return false;
        if (failing.eligible() != null) return failing.eligible();
        return !"MISSING".equals(String.valueOf(failing.actualValue()));
    }

    private boolean isInCooldown(Rule rule, String scopeKey) {
        if (rule.cooldownSeconds() == null) {
            return false;
        }
        Instant last = lastMatchedAt.get(cooldownKey(rule.ruleId(), scopeKey));
        if (last == null) {
            return false;
        }
        return clock.instant().isBefore(last.plusSeconds(rule.cooldownSeconds()));
    }
}
