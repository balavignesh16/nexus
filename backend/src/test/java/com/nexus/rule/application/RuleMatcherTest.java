package com.nexus.rule.application;

import com.nexus.event.application.EventDispatcher;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.rule.domain.RuleMatchedEvent;
import com.nexus.rule.persistence.BoundedRuleMatchStore;
import com.nexus.rule.persistence.RuleMatchPersister;
import com.nexus.rule.persistence.RuleMatchRepository;
import com.nexus.rule.persistence.RuleRegistry;
import com.nexus.realtime.RealtimePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
    private RuleMatchPersister ruleMatchPersister;
    private RuleMatchRepository ruleMatchRepository;
    private RuleMatcher ruleMatcher;

    @BeforeEach
    void setUp() {
        eventDispatcher = mock(EventDispatcher.class);
        ruleRegistry = mock(RuleRegistry.class);
        ruleEvaluator = mock(RuleEvaluator.class);
        matchStore = mock(BoundedRuleMatchStore.class);
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        realtimePublisher = mock(RealtimePublisher.class);
        ruleMatchPersister = mock(RuleMatchPersister.class);
        ruleMatchRepository = mock(RuleMatchRepository.class);
        when(ruleMatchRepository.findLastMatchedAtByRule()).thenReturn(List.of());
        ruleMatcher = new RuleMatcher(eventDispatcher, ruleRegistry, ruleEvaluator, matchStore, applicationEventPublisher, realtimePublisher, ruleMatchPersister, ruleMatchRepository);
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

    // ---------- Tier 3 Phase 9B: durable persistence ordering/failure semantics ----------

    @Test
    void persistenceOccursBeforeCacheRealtimeAndApplicationEvent() {
        DomainEvent event = sampleEvent("corr-order");
        Rule rule = sampleRule("Ordered Rule");
        RuleEvaluationResult matchedResult = new RuleEvaluationResult(true, rule, event, "All conditions met");

        when(ruleRegistry.listEnabledOrderedByPriority()).thenReturn(List.of(rule));
        when(ruleEvaluator.evaluate(rule, event)).thenReturn(matchedResult);

        ruleMatcher.onEvent(event);

        InOrder order = inOrder(ruleMatchPersister, matchStore, realtimePublisher, applicationEventPublisher);
        order.verify(ruleMatchPersister).persist(any());
        order.verify(matchStore).add(any());
        order.verify(realtimePublisher).publishRuleMatch(any(), any());
        order.verify(applicationEventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void persistenceFailurePreventsDownstreamButOtherRulesContinue() {
        DomainEvent event = sampleEvent("corr-fail");
        Rule failingMatchRule = sampleRule("Persistence Fails");
        Rule healthyMatchRule = sampleRule("Persists Fine");
        RuleEvaluationResult failingResult = new RuleEvaluationResult(true, failingMatchRule, event, "All conditions met");
        RuleEvaluationResult healthyResult = new RuleEvaluationResult(true, healthyMatchRule, event, "All conditions met");

        when(ruleRegistry.listEnabledOrderedByPriority()).thenReturn(List.of(failingMatchRule, healthyMatchRule));
        when(ruleEvaluator.evaluate(failingMatchRule, event)).thenReturn(failingResult);
        when(ruleEvaluator.evaluate(healthyMatchRule, event)).thenReturn(healthyResult);
        doThrow(new RuntimeException("simulated persistence failure"))
                .when(ruleMatchPersister).persist(argThat(m -> m.result().rule().equals(failingMatchRule)));

        assertDoesNotThrow(() -> ruleMatcher.onEvent(event));

        // The failing rule's match must never reach the cache, realtime, or the application event bus.
        ArgumentCaptor<RuleMatchedEvent> matchStoreCaptor = ArgumentCaptor.forClass(RuleMatchedEvent.class);
        verify(matchStore, times(1)).add(matchStoreCaptor.capture());
        assertEquals(healthyMatchRule, matchStoreCaptor.getValue().result().rule(),
                "only the healthy rule's match may reach the cache");

        verify(realtimePublisher, never()).publishRuleMatch(eq(failingResult), any());
        verify(realtimePublisher, times(1)).publishRuleMatch(eq(healthyResult), any());

        ArgumentCaptor<Object> publishedEventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher, times(1)).publishEvent(publishedEventCaptor.capture());
        RuleMatchedEvent publishedEvent = (RuleMatchedEvent) publishedEventCaptor.getValue();
        assertEquals(healthyMatchRule, publishedEvent.result().rule(),
                "only the healthy rule's match may reach the application event bus");
    }

    // ---------- Phase 13: cooldown ----------

    private Rule sampleRuleWithCooldown(String name, int cooldownSeconds) {
        return new Rule(UUID.randomUUID(), name, true, 1, List.of(), List.of(), Map.of(), cooldownSeconds);
    }

    @Test
    void cooldown_null_everyMatchingEventStillTriggersAMatch() {
        DomainEvent event = sampleEvent("corr-cd-null");
        Rule rule = sampleRule("No Cooldown"); // cooldownSeconds == null
        when(ruleRegistry.listEnabledOrderedByPriority()).thenReturn(List.of(rule));
        when(ruleEvaluator.evaluate(rule, event)).thenReturn(new RuleEvaluationResult(true, rule, event, "matched"));

        ruleMatcher.onEvent(event);
        ruleMatcher.onEvent(event);

        verify(matchStore, times(2)).add(any());
    }

    @Test
    void cooldown_setSeconds_blocksASecondMatchWithinTheWindow() {
        DomainEvent event = sampleEvent("corr-cd-block");
        Rule rule = sampleRuleWithCooldown("Cooldown Rule", 60);
        when(ruleRegistry.listEnabledOrderedByPriority()).thenReturn(List.of(rule));
        when(ruleEvaluator.evaluate(rule, event)).thenReturn(new RuleEvaluationResult(true, rule, event, "matched"));

        ruleMatcher.onEvent(event);
        ruleMatcher.onEvent(event);

        verify(matchStore, times(1)).add(any());
        verify(ruleEvaluator, times(1)).evaluate(rule, event); // second call must be gated before even evaluating
    }

    @Test
    void cooldown_isPerRule_doesNotAffectAnUnrelatedRule() {
        DomainEvent event = sampleEvent("corr-cd-perrule");
        Rule cooledDown = sampleRuleWithCooldown("Cooled Down", 60);
        Rule other = sampleRule("Unaffected Rule");
        when(ruleRegistry.listEnabledOrderedByPriority()).thenReturn(List.of(cooledDown, other));
        when(ruleEvaluator.evaluate(cooledDown, event)).thenReturn(new RuleEvaluationResult(true, cooledDown, event, "matched"));
        when(ruleEvaluator.evaluate(other, event)).thenReturn(new RuleEvaluationResult(true, other, event, "matched"));

        ruleMatcher.onEvent(event); // cooledDown matches once, enters cooldown
        ruleMatcher.onEvent(event); // cooledDown gated; other still matches again

        verify(ruleEvaluator, times(1)).evaluate(cooledDown, event);
        verify(ruleEvaluator, times(2)).evaluate(other, event);
    }

    @Test
    void cooldown_persistenceFailure_doesNotPoisonCooldownState_nextEventStillEvaluated() {
        DomainEvent event = sampleEvent("corr-cd-failsafe");
        Rule rule = sampleRuleWithCooldown("Flaky Persist", 60);
        when(ruleRegistry.listEnabledOrderedByPriority()).thenReturn(List.of(rule));
        when(ruleEvaluator.evaluate(rule, event)).thenReturn(new RuleEvaluationResult(true, rule, event, "matched"));
        doThrow(new RuntimeException("simulated persistence failure")).when(ruleMatchPersister).persist(any());

        ruleMatcher.onEvent(event); // persist throws - cooldown must NOT be armed
        ruleMatcher.onEvent(event); // must still attempt to evaluate/persist again, not be gated by a phantom cooldown

        verify(ruleEvaluator, times(2)).evaluate(rule, event);
        verify(matchStore, never()).add(any());
    }

    @Test
    void init_hydratesCooldownState_fromDurableRuleMatchRepository() {
        UUID ruleId = UUID.randomUUID();
        Instant recentMatch = Instant.now().minusSeconds(5);
        RuleMatchRepository freshRepository = mock(RuleMatchRepository.class);
        when(freshRepository.findLastMatchedAtByRule()).thenReturn(java.util.Collections.singletonList(new Object[]{ruleId, recentMatch}));
        RuleMatcher matcherWithHistory = new RuleMatcher(eventDispatcher, ruleRegistry, ruleEvaluator, matchStore,
                applicationEventPublisher, realtimePublisher, ruleMatchPersister, freshRepository);

        matcherWithHistory.init();

        DomainEvent event = sampleEvent("corr-cd-restart");
        Rule rehydratedRule = new Rule(ruleId, "Rehydrated", true, 1, List.of(), List.of(), Map.of(), 60);
        when(ruleRegistry.listEnabledOrderedByPriority()).thenReturn(List.of(rehydratedRule));
        when(ruleEvaluator.evaluate(rehydratedRule, event)).thenReturn(new RuleEvaluationResult(true, rehydratedRule, event, "matched"));

        matcherWithHistory.onEvent(event);

        // The rule matched 5s ago (within its 60s cooldown) per durable
        // history - a freshly-constructed RuleMatcher (simulating a restart)
        // must honor that without ever evaluating this event for this rule.
        verify(ruleEvaluator, never()).evaluate(rehydratedRule, event);
    }
}
