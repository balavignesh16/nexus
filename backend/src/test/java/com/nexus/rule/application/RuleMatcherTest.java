package com.nexus.rule.application;

import com.nexus.event.application.EventDispatcher;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.rule.domain.RuleMatchedEvent;
import com.nexus.rule.persistence.BoundedRuleMatchStore;
import com.nexus.rule.persistence.RuleRegistry;
import com.nexus.realtime.RealtimePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Behavioral tests for RuleMatcher's orchestration logic: iterating enabled
 * rules, branching on RuleEvaluator's result, storing/publishing matches, and
 * isolating one rule's evaluation failure from the rest. RuleEvaluator itself
 * is mocked here (its real logic is covered exhaustively in
 * RuleEvaluatorTest) so these tests focus purely on what RuleMatcher does
 * with the evaluator's result.
 */
class RuleMatcherTest {

    private EventDispatcher eventDispatcher;
    private RuleRegistry ruleRegistry;
    private RuleEvaluator ruleEvaluator;
    private BoundedRuleMatchStore matchStore;
    private ApplicationEventPublisher applicationEventPublisher;
    private RealtimePublisher realtimePublisher;
    private RuleMatcher ruleMatcher;

    @BeforeEach
    void setUp() {
        eventDispatcher = mock(EventDispatcher.class);
        ruleRegistry = mock(RuleRegistry.class);
        ruleEvaluator = mock(RuleEvaluator.class);
        matchStore = mock(BoundedRuleMatchStore.class);
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        realtimePublisher = mock(RealtimePublisher.class);
        ruleMatcher = new RuleMatcher(eventDispatcher, ruleRegistry, ruleEvaluator, matchStore, applicationEventPublisher, realtimePublisher);
    }

    private DomainEvent sampleEvent(String correlationId) {
        return new DomainEvent(UUID.randomUUID(), EventType.TWIN_UPDATED, UUID.randomUUID(),
                Instant.now(), "test", Map.of(), "INFO", correlationId);
    }

    private Rule sampleRule(String name) {
        return new Rule(UUID.randomUUID(), name, true, 1, List.of(), List.of(), Map.of());
    }

    @Test
    void onEvent_ruleMatches_storesAndPublishesMatchedEvent() {
        DomainEvent event = sampleEvent("corr-1");
        Rule rule = sampleRule("Matching Rule");
        RuleEvaluationResult matchedResult = new RuleEvaluationResult(true, rule, event, "All conditions met");

        when(ruleRegistry.listEnabledOrderedByPriority()).thenReturn(List.of(rule));
        when(ruleEvaluator.evaluate(rule, event)).thenReturn(matchedResult);

        ruleMatcher.onEvent(event);

        ArgumentCaptor<RuleMatchedEvent> captor = ArgumentCaptor.forClass(RuleMatchedEvent.class);
        verify(matchStore).add(captor.capture());
        assertEquals(matchedResult, captor.getValue().result());
        assertEquals("corr-1", captor.getValue().correlationId());

        verify(applicationEventPublisher).publishEvent(captor.getValue());
        verify(realtimePublisher).publishRuleMatch(matchedResult, "corr-1");
    }

    @Test
    void onEvent_ruleDoesNotMatch_doesNotStoreOrPublish() {
        DomainEvent event = sampleEvent("corr-2");
        Rule rule = sampleRule("Non-matching Rule");
        RuleEvaluationResult unmatchedResult = new RuleEvaluationResult(false, rule, event, "Condition not met: VALUE");

        when(ruleRegistry.listEnabledOrderedByPriority()).thenReturn(List.of(rule));
        when(ruleEvaluator.evaluate(rule, event)).thenReturn(unmatchedResult);

        ruleMatcher.onEvent(event);

        verify(matchStore, never()).add(any());
        verify(applicationEventPublisher, never()).publishEvent(any());
        verify(realtimePublisher, never()).publishRuleMatch(any(), any());
    }

    @Test
    void onEvent_multipleRules_onlyMatchingOnesProduceMatchedEvents() {
        DomainEvent event = sampleEvent("corr-3");
        Rule matchingRule1 = sampleRule("Match 1");
        Rule nonMatchingRule = sampleRule("No Match");
        Rule matchingRule2 = sampleRule("Match 2");

        when(ruleRegistry.listEnabledOrderedByPriority()).thenReturn(List.of(matchingRule1, nonMatchingRule, matchingRule2));
        when(ruleEvaluator.evaluate(matchingRule1, event)).thenReturn(new RuleEvaluationResult(true, matchingRule1, event, "matched"));
        when(ruleEvaluator.evaluate(nonMatchingRule, event)).thenReturn(new RuleEvaluationResult(false, nonMatchingRule, event, "not matched"));
        when(ruleEvaluator.evaluate(matchingRule2, event)).thenReturn(new RuleEvaluationResult(true, matchingRule2, event, "matched"));

        ruleMatcher.onEvent(event);

        ArgumentCaptor<RuleMatchedEvent> captor = ArgumentCaptor.forClass(RuleMatchedEvent.class);
        verify(matchStore, times(2)).add(captor.capture());
        List<Rule> matchedRules = captor.getAllValues().stream().map(m -> m.result().rule()).toList();
        assertTrue(matchedRules.contains(matchingRule1));
        assertTrue(matchedRules.contains(matchingRule2));
        assertFalse(matchedRules.contains(nonMatchingRule));
    }

    @Test
    void onEvent_evaluatorThrowsForOneRule_remainingRulesAreStillEvaluated() {
        DomainEvent event = sampleEvent("corr-4");
        Rule failingRule = sampleRule("Throws during evaluation");
        Rule healthyRule = sampleRule("Evaluates fine and matches");

        when(ruleRegistry.listEnabledOrderedByPriority()).thenReturn(List.of(failingRule, healthyRule));
        when(ruleEvaluator.evaluate(failingRule, event)).thenThrow(new RuntimeException("simulated evaluator failure"));
        when(ruleEvaluator.evaluate(healthyRule, event)).thenReturn(new RuleEvaluationResult(true, healthyRule, event, "matched"));

        // Must not propagate the exception - RuleMatcher.onEvent is invoked
        // synchronously from EventDispatcher.publish() and must not abort
        // dispatch to other rules/listeners.
        assertDoesNotThrow(() -> ruleMatcher.onEvent(event));

        ArgumentCaptor<RuleMatchedEvent> captor = ArgumentCaptor.forClass(RuleMatchedEvent.class);
        verify(matchStore, times(1)).add(captor.capture());
        assertEquals(healthyRule, captor.getValue().result().rule());
    }

    @Test
    void onEvent_noEnabledRules_doesNothing() {
        DomainEvent event = sampleEvent("corr-5");
        when(ruleRegistry.listEnabledOrderedByPriority()).thenReturn(List.of());

        ruleMatcher.onEvent(event);

        verifyNoInteractions(ruleEvaluator);
        verifyNoInteractions(matchStore);
        verifyNoInteractions(applicationEventPublisher);
        verifyNoInteractions(realtimePublisher);
    }

    @Test
    void init_subscribesItselfToEventDispatcher() {
        ruleMatcher.init();

        verify(eventDispatcher).subscribe(ruleMatcher);
    }

    @Test
    void cleanup_unregistersItselfFromEventDispatcher() {
        ruleMatcher.cleanup();

        verify(eventDispatcher).unregister(ruleMatcher);
    }
}
