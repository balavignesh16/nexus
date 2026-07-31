package com.nexus.action.application;

import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionType;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.rule.domain.RuleMatchedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ActionPlannerTest {

    private ActionExecutionEngine executionEngine;
    private ActionPlanner planner;

    @BeforeEach
    void setUp() {
        executionEngine = mock(ActionExecutionEngine.class);
        planner = new ActionPlanner(executionEngine);
    }

    @Test
    void shouldConvertRuleActionsToExecutionRequests() {
        DomainEvent event = new DomainEvent(
                UUID.randomUUID(), EventType.SENSOR_VALUE_CHANGED, UUID.randomUUID(),
                Instant.now(), "Test", Map.of(), "INFO", "corr-111"
        );

        Rule rule = new Rule(
                UUID.randomUUID(), "Test Rule", true, 1, List.of(),
                List.of(
                        new RuleAction("CREATE_ALERT", Map.of("severity", "CRITICAL")),
                        new RuleAction("WEBHOOK", Map.of("url", "http://example.com"))
                ),
                Map.of()
        );

        RuleEvaluationResult result = new RuleEvaluationResult(true, rule, event, "Matched");
        RuleMatchedEvent matchedEvent = new RuleMatchedEvent(UUID.randomUUID(), Instant.now(), result, "corr-111");

        planner.onRuleMatched(matchedEvent);

        ArgumentCaptor<List<ActionExecutionRequest>> captor = ArgumentCaptor.forClass(List.class);
        verify(executionEngine).process(captor.capture(), any(ActionContext.class), eq("corr-111"));

        List<ActionExecutionRequest> requests = captor.getValue();
        assertEquals(2, requests.size());
        assertEquals(ActionType.CREATE_ALERT, requests.get(0).actionType());
        assertEquals(ActionType.WEBHOOK, requests.get(1).actionType());
    }

    @Test
    void shouldIgnoreUnknownActionTypesAndProcessValidOnes() {
        DomainEvent event = new DomainEvent(
                UUID.randomUUID(), EventType.SENSOR_VALUE_CHANGED, UUID.randomUUID(),
                Instant.now(), "Test", Map.of(), "INFO", "corr-222"
        );

        Rule rule = new Rule(
                UUID.randomUUID(), "Test Rule", true, 1, List.of(),
                List.of(
                        new RuleAction("UNKNOWN_TYPE", Map.of()),
                        new RuleAction("SEND_COMMAND", Map.of("cmd", "reset"))
                ),
                Map.of()
        );

        RuleEvaluationResult result = new RuleEvaluationResult(true, rule, event, "Matched");
        RuleMatchedEvent matchedEvent = new RuleMatchedEvent(UUID.randomUUID(), Instant.now(), result, "corr-222");

        planner.onRuleMatched(matchedEvent);

        ArgumentCaptor<List<ActionExecutionRequest>> captor = ArgumentCaptor.forClass(List.class);
        verify(executionEngine).process(captor.capture(), any(ActionContext.class), eq("corr-222"));

        List<ActionExecutionRequest> requests = captor.getValue();
        assertEquals(1, requests.size());
        assertEquals(ActionType.SEND_COMMAND, requests.get(0).actionType());
    }
}
