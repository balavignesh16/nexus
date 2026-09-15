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
    private final ConcurrentHashMap<UUID, Instant> lastMatchedAt = new ConcurrentHashMap<>();
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
            lastMatchedAt.put(ruleId, matchedAt);
        }
    }

    @PreDestroy
    public void cleanup() {
        eventDispatcher.unregister(this);
    }

    @Override
    public void onEvent(DomainEvent event) {
        List<Rule> activeRules = ruleRegistry.listEnabledOrderedByPriority();
        for (Rule rule : activeRules) {
            Object lock = ruleLocks.computeIfAbsent(rule.ruleId(), id -> new Object());
            synchronized (lock) {
                try {
                    if (isInCooldown(rule)) {
                        continue;
                    }

                    RuleEvaluationResult result = ruleEvaluator.evaluate(rule, event);
                    if (result.matched()) {
                        log.info("Rule matched! Rule: '{}', Event: {}", rule.name(), event.eventType());
                        RuleMatchedEvent matchedEvent = new RuleMatchedEvent(
                                UUID.randomUUID(),
                                Instant.now(),
                                result,
                                event.correlationId()
                        );

                        // Persist first - only once this line returns
                        // successfully has the match been durably recorded.
                        // Everything below, including the cooldown-timestamp
                        // update, is unreachable if persist() throws, so a
                        // persistence failure can never create a cooldown
                        // window for a match that was never actually recorded.
                        ruleMatchPersister.persist(matchedEvent);

                        if (rule.cooldownSeconds() != null) {
                            lastMatchedAt.put(rule.ruleId(), matchedEvent.timestamp());
                        }

                        matchStore.add(matchedEvent);
                        realtimePublisher.publishRuleMatch(result, event.correlationId());
                        applicationEventPublisher.publishEvent(matchedEvent);
                    }
                } catch (Exception e) {
                    log.error("Failed to evaluate rule {} against event {}", rule.ruleId(), event.eventId(), e);
                }
            }
        }
    }

    private boolean isInCooldown(Rule rule) {
        if (rule.cooldownSeconds() == null) {
            return false;
        }
        Instant last = lastMatchedAt.get(rule.ruleId());
        if (last == null) {
            return false;
        }
        return Instant.now().isBefore(last.plusSeconds(rule.cooldownSeconds()));
    }
}
