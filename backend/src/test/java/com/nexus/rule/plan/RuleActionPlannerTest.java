package com.nexus.rule.plan;

import com.nexus.action.application.ActionExecutionEngine;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionType;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.rule.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit coverage for the single action authority (replaces the retired legacy ActionPlanner's unit tests): only SELECTED decisions of a
 * PERSISTED plan reach the engine, a failed or already-made decision dispatches nothing, and a legacy match keeps its triggering event.
 */
class RuleActionPlannerTest {
    private RuleActionPlanStore store;
    private ActionExecutionEngine engine;
    private RuleActionPlanner planner;
    private static final UUID DEVICE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @BeforeEach void setUp() {
        store = mock(RuleActionPlanStore.class);
        engine = mock(ActionExecutionEngine.class);
        planner = new RuleActionPlanner(store, engine);
    }

    private static Rule pinned(RuleAction... actions) {
        return new Rule(UUID.randomUUID(), "r", true, 1, List.of(), List.of(actions), Map.of(), null, new RuleExecutionIdentity(UUID.randomUUID(), 1, 1));
    }
    private static RuleAction led(boolean on) { return new RuleAction("SEND_COMMAND", Map.of("deviceId", DEVICE.toString(), "capability", "LED", "command", "SET_STATE", "value", on)); }
    private static RuleMatchedEvent match(Rule rule, DomainEvent event) {
        return new RuleMatchedEvent(UUID.randomUUID(), java.time.Instant.now(), new RuleEvaluationResult(true, rule, event, "ok"), "corr");
    }
    private static DomainEvent event() { return new DomainEvent(UUID.randomUUID(), EventType.TWIN_UPDATED, DEVICE, java.time.Instant.now(), "t", Map.of(), "INFO", "c"); }

    @SuppressWarnings("unchecked")
    private List<ActionCandidate> persistedCandidates(PlanSource source) {
        ArgumentCaptor<List<ActionCandidate>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).persist(eq(source), captor.capture());
        return captor.getValue();
    }

    @Test void onlySelectedDecisionsOfAPersistedPlanReachTheEngine() {
        var rule = pinned(led(true), new RuleAction("CREATE_ALERT", Map.of()));
        var event = event();
        var source = PlanSource.event(event.eventId());
        when(store.persist(eq(source), any())).thenAnswer(inv -> {
            List<ActionCandidate> candidates = inv.getArgument(1);
            var decisions = List.of(new ActionDecision(candidates.get(0), ActionDecision.Disposition.SUPPRESSED_PRIORITY, "x", null),
                new ActionDecision(candidates.get(1), ActionDecision.Disposition.SELECTED, "INDEPENDENT_TARGET", null));
            return Optional.of(new RuleActionPlanStore.Plan(UUID.randomUUID(), decisions));
        });
        planner.planAndDispatch(source, List.of(match(rule, event)));
        var requests = ArgumentCaptor.forClass(List.class);
        var contexts = ArgumentCaptor.forClass(ActionContext.class);
        verify(engine, times(1)).process(requests.capture(), contexts.capture(), eq("corr"));   // the match's correlation id reaches the engine
        var dispatched = (ActionExecutionRequest) requests.getValue().getFirst();
        assertEquals(ActionType.CREATE_ALERT, dispatched.actionType(), "the suppressed command never reached the engine");
        assertSame(event, contexts.getValue().triggeringEvent(), "a legacy match keeps its triggering DomainEvent");
        assertEquals(rule.ruleId(), contexts.getValue().matchedRule().ruleId());
        assertEquals(persistedCandidates(source).get(1).requestId(), dispatched.requestId(), "the engine request id is the persisted candidate's");
        verify(store).recordOutcomes(any());
    }

    @Test void aFailedPlanPersistenceDispatchesNothingAndSurfacesTheFailure() {
        var event = event();
        when(store.persist(any(), any())).thenThrow(new IllegalStateException("plan outage"));
        assertThrows(IllegalStateException.class, () -> planner.planAndDispatch(PlanSource.event(event.eventId()), List.of(match(pinned(led(true)), event))));
        verifyNoInteractions(engine);
    }

    @Test void anAlreadyDecidedBatchDispatchesNothingAgain() {
        var event = event();
        when(store.persist(any(), any())).thenReturn(Optional.empty());
        planner.planAndDispatch(PlanSource.event(event.eventId()), List.of(match(pinned(led(true)), event)));
        verifyNoInteractions(engine);
    }

    @Test void anUnpinnedRuleHasNoCandidatesBecauseItCanNeverBeAuthorized() {
        var event = event();
        var unpinned = new Rule(UUID.randomUUID(), "r", true, 1, List.of(), List.of(led(true)), Map.of());
        when(store.persist(any(), any())).thenReturn(Optional.empty());
        planner.planAndDispatch(PlanSource.event(event.eventId()), List.of(match(unpinned, event)));
        assertTrue(persistedCandidates(PlanSource.event(event.eventId())).isEmpty());
    }

    @Test void anUnknownActionTypeIsSkippedWithoutBlockingTheRest() {
        var rule = pinned(new RuleAction("TELEPORT", Map.of()), new RuleAction("CREATE_ALERT", Map.of()));
        var event = event();
        var source = PlanSource.event(event.eventId());
        when(store.persist(eq(source), any())).thenAnswer(inv -> {
            List<ActionCandidate> candidates = inv.getArgument(1);
            return Optional.of(new RuleActionPlanStore.Plan(UUID.randomUUID(), candidates.stream()
                .map(c -> new ActionDecision(c, ActionDecision.Disposition.SELECTED, "INDEPENDENT_TARGET", null)).toList()));
        });
        planner.planAndDispatch(source, List.of(match(rule, event)));
        verify(engine, times(1)).process(any(), any(), any());
    }

    @Test void aFailingEngineCallDoesNotStopLaterSelectedActions() {
        var rule = pinned(new RuleAction("CREATE_ALERT", Map.of()), new RuleAction("WEBHOOK", Map.of()));
        var event = event();
        var source = PlanSource.event(event.eventId());
        when(store.persist(eq(source), any())).thenAnswer(inv -> {
            List<ActionCandidate> candidates = inv.getArgument(1);
            return Optional.of(new RuleActionPlanStore.Plan(UUID.randomUUID(), candidates.stream()
                .map(c -> new ActionDecision(c, ActionDecision.Disposition.SELECTED, "INDEPENDENT_TARGET", null)).toList()));
        });
        doThrow(new IllegalStateException("engine failure")).doNothing().when(engine).process(any(), any(), any());
        assertDoesNotThrow(() -> planner.planAndDispatch(source, List.of(match(rule, event))));
        verify(engine, times(2)).process(any(), any(), any());
    }
}
