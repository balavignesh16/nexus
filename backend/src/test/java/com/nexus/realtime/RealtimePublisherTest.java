package com.nexus.realtime;

import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleEvaluationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for RealtimePublisher's broadcast contract: correct topic routing,
 * the RuleMatched "unmatched results are not broadcast" guard, and - most
 * importantly - that a failure while broadcasting (e.g. the messaging layer
 * throwing) is caught internally and never propagates to the caller. Callers
 * (TelemetryService, RuleMatcher, ActionExecutionEngine, etc.) must be able
 * to call these publish methods without any try/catch of their own.
 */
class RealtimePublisherTest {

    private SimpMessagingTemplate messaging;
    private RealtimeBuffer buffer;
    private RealtimeMetrics metrics;
    private RealtimePublisher publisher;

    @BeforeEach
    void setUp() {
        messaging = mock(SimpMessagingTemplate.class);
        buffer = mock(RealtimeBuffer.class);
        metrics = mock(RealtimeMetrics.class);
        publisher = new RealtimePublisher(messaging, buffer, metrics);
    }

    private DomainEvent sampleEvent(String correlationId) {
        return new DomainEvent(UUID.randomUUID(), EventType.TWIN_UPDATED, UUID.randomUUID(),
                Instant.now(), "test", Map.of(), "INFO", correlationId);
    }

    private Rule sampleRule() {
        return new Rule(UUID.randomUUID(), "Test Rule", true, 1, List.of(), List.of(), Map.of());
    }

    @Test
    void publishRuleMatch_matchedResult_broadcastsToRulesTopic() {
        DomainEvent event = sampleEvent("corr-1");
        Rule rule = sampleRule();
        RuleEvaluationResult matched = new RuleEvaluationResult(true, rule, event, "All conditions met");

        publisher.publishRuleMatch(matched, "corr-1");

        verify(messaging).convertAndSend(eq(RealtimeTopics.RULES), any(RealtimeMessage.class));
        verify(buffer).append(eq(RealtimeTopics.RULES), any(RealtimeMessage.class));
        verify(metrics).recordPublishedMessage();
    }

    @Test
    void publishRuleMatch_unmatchedResult_doesNotBroadcast() {
        DomainEvent event = sampleEvent("corr-2");
        Rule rule = sampleRule();
        RuleEvaluationResult unmatched = new RuleEvaluationResult(false, rule, event, "Condition not met");

        publisher.publishRuleMatch(unmatched, "corr-2");

        verifyNoInteractions(messaging);
        verifyNoInteractions(buffer);
        verifyNoInteractions(metrics);
    }

    @Test
    void publishRuleMatch_messagingThrows_isCaughtAndDoesNotPropagate() {
        DomainEvent event = sampleEvent("corr-3");
        Rule rule = sampleRule();
        RuleEvaluationResult matched = new RuleEvaluationResult(true, rule, event, "All conditions met");

        doThrow(new RuntimeException("broker unreachable"))
                .when(messaging).convertAndSend(eq(RealtimeTopics.RULES), any(RealtimeMessage.class));

        assertDoesNotThrow(() -> publisher.publishRuleMatch(matched, "corr-3"));

        verify(metrics).recordDroppedMessage();
        verify(metrics, never()).recordPublishedMessage();
    }

    @Test
    void publishEvent_messagingThrows_isCaughtAndDoesNotPropagate() {
        DomainEvent event = sampleEvent("corr-4");

        doThrow(new RuntimeException("broker unreachable"))
                .when(messaging).convertAndSend(eq(RealtimeTopics.EVENTS), any(RealtimeMessage.class));

        assertDoesNotThrow(() -> publisher.publishEvent(event, "corr-4"));

        verify(metrics).recordDroppedMessage();
        verify(metrics, never()).recordPublishedMessage();
    }
}
