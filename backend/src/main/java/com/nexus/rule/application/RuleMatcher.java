package com.nexus.rule.application;

import com.nexus.event.application.EventDispatcher;
import com.nexus.event.application.EventListener;
import com.nexus.event.domain.DomainEvent;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.rule.domain.RuleMatchedEvent;
import com.nexus.rule.persistence.BoundedRuleMatchStore;
import com.nexus.rule.persistence.RuleRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class RuleMatcher implements EventListener {

    private static final Logger log = LoggerFactory.getLogger(RuleMatcher.class);

    private final EventDispatcher eventDispatcher;
    private final RuleRegistry ruleRegistry;
    private final RuleEvaluator ruleEvaluator;
    private final BoundedRuleMatchStore matchStore;
    private final ApplicationEventPublisher applicationEventPublisher;

    public RuleMatcher(EventDispatcher eventDispatcher, RuleRegistry ruleRegistry, RuleEvaluator ruleEvaluator, BoundedRuleMatchStore matchStore, ApplicationEventPublisher applicationEventPublisher) {
        this.eventDispatcher = eventDispatcher;
        this.ruleRegistry = ruleRegistry;
        this.ruleEvaluator = ruleEvaluator;
        this.matchStore = matchStore;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @PostConstruct
    public void init() {
        eventDispatcher.subscribe(this);
    }

    @PreDestroy
    public void cleanup() {
        eventDispatcher.unregister(this);
    }

    @Override
    public void onEvent(DomainEvent event) {
        List<Rule> activeRules = ruleRegistry.listEnabledOrderedByPriority();
        for (Rule rule : activeRules) {
            try {
                RuleEvaluationResult result = ruleEvaluator.evaluate(rule, event);
                if (result.matched()) {
                    log.info("Rule matched! Rule: '{}', Event: {}", rule.name(), event.eventType());
                    RuleMatchedEvent matchedEvent = new RuleMatchedEvent(
                            UUID.randomUUID(),
                            Instant.now(),
                            result,
                            event.correlationId()
                    );
                    matchStore.add(matchedEvent);
                    applicationEventPublisher.publishEvent(matchedEvent);
                }
            } catch (Exception e) {
                log.error("Failed to evaluate rule {} against event {}", rule.ruleId(), event.eventId(), e);
            }
        }
    }
}
