package com.nexus.rule.ai;

import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.DomainEventRepository;
import com.nexus.event.domain.EventType;
import com.nexus.rule.persistence.RuleMatchEntity;
import com.nexus.rule.persistence.RuleMatchRepository;
import com.nexus.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleMatchExplanationContextBuilderTest {

    private RuleMatchRepository ruleMatchRepository;
    private DomainEventRepository domainEventRepository;
    private ActionHistoryRepository actionHistoryRepository;
    private RuleMatchExplanationContextBuilder builder;

    @BeforeEach
    void setUp() {
        ruleMatchRepository = mock(RuleMatchRepository.class);
        domainEventRepository = mock(DomainEventRepository.class);
        actionHistoryRepository = mock(ActionHistoryRepository.class);
        builder = new RuleMatchExplanationContextBuilder(ruleMatchRepository, domainEventRepository, actionHistoryRepository);
    }

    private RuleMatchEntity sampleMatch(UUID matchId, UUID eventId) {
        return new RuleMatchEntity(
                matchId, UUID.randomUUID(),
                Map.of("name", "Test Rule", "conditions", List.of(Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0"))),
                eventId, "corr-1",
                Map.of("outcome", "matched", "evaluatedConditions", List.of(Map.of("field", "VALUE", "actualValue", 34.7, "conditionMet", true))),
                Instant.now(), "All conditions met"
        );
    }

    @Test
    void validFullContext_assembledCorrectly() {
        UUID matchId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        RuleMatchEntity match = sampleMatch(matchId, eventId);
        DomainEvent event = new DomainEvent(eventId, EventType.TWIN_UPDATED, deviceId, Instant.now(), "test", Map.of(), "INFO", "corr-1");

        when(ruleMatchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(domainEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(actionHistoryRepository.findByMatchId(matchId)).thenReturn(List.of());

        RuleMatchExplanationContext context = builder.build(matchId);

        assertEquals(matchId, context.matchId());
        assertEquals(match.getRuleId(), context.ruleId());
        assertEquals(deviceId, context.deviceId());
        assertEquals("Test Rule", context.ruleSnapshot().get("name"));
        assertTrue(context.actions().isEmpty());
    }

    @Test
    void deviceResolution_readFromDomainEvent() {
        UUID matchId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        RuleMatchEntity match = sampleMatch(matchId, eventId);
        DomainEvent event = new DomainEvent(eventId, EventType.TWIN_UPDATED, deviceId, Instant.now(), "test", Map.of(), "INFO", "corr-1");

        when(ruleMatchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(domainEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(actionHistoryRepository.findByMatchId(matchId)).thenReturn(List.of());

        RuleMatchExplanationContext context = builder.build(matchId);

        assertEquals(deviceId, context.deviceId());
        assertEquals(eventId, context.domainEvent().eventId());
        assertEquals("TWIN_UPDATED", context.domainEvent().eventType());
    }

    @Test
    void missingDomainEvent_deviceIdAndDomainEventAreNull_noException() {
        UUID matchId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        RuleMatchEntity match = sampleMatch(matchId, eventId);

        when(ruleMatchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(domainEventRepository.findById(eventId)).thenReturn(Optional.empty());
        when(actionHistoryRepository.findByMatchId(matchId)).thenReturn(List.of());

        RuleMatchExplanationContext context = builder.build(matchId);

        assertNull(context.deviceId());
        assertNull(context.domainEvent());
    }

    @Test
    void multipleActions_allIncluded() {
        UUID matchId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        RuleMatchEntity match = sampleMatch(matchId, eventId);
        ActionHistoryRecord a1 = new ActionHistoryRecord(UUID.randomUUID(), matchId, null, ActionType.CREATE_ALERT, ExecutionStatus.SUCCESS, Map.of(), Instant.now(), "ok1");
        ActionHistoryRecord a2 = new ActionHistoryRecord(UUID.randomUUID(), matchId, null, ActionType.WEBHOOK, ExecutionStatus.SUCCESS, Map.of(), Instant.now(), "ok2");

        when(ruleMatchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(domainEventRepository.findById(eventId)).thenReturn(Optional.empty());
        when(actionHistoryRepository.findByMatchId(matchId)).thenReturn(List.of(a1, a2));

        RuleMatchExplanationContext context = builder.build(matchId);

        assertEquals(2, context.actions().size());
    }

    @Test
    void singleAction_included() {
        UUID matchId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        RuleMatchEntity match = sampleMatch(matchId, eventId);
        ActionHistoryRecord action = new ActionHistoryRecord(UUID.randomUUID(), matchId, null, ActionType.CREATE_ALERT, ExecutionStatus.SUCCESS, Map.of(), Instant.now(), "ok");

        when(ruleMatchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(domainEventRepository.findById(eventId)).thenReturn(Optional.empty());
        when(actionHistoryRepository.findByMatchId(matchId)).thenReturn(List.of(action));

        RuleMatchExplanationContext context = builder.build(matchId);

        assertEquals(1, context.actions().size());
        assertEquals("CREATE_ALERT", context.actions().get(0).actionType());
        assertEquals("SUCCESS", context.actions().get(0).status());
    }

    @Test
    void noActions_emptyList() {
        UUID matchId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        RuleMatchEntity match = sampleMatch(matchId, eventId);

        when(ruleMatchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(domainEventRepository.findById(eventId)).thenReturn(Optional.empty());
        when(actionHistoryRepository.findByMatchId(matchId)).thenReturn(List.of());

        RuleMatchExplanationContext context = builder.build(matchId);

        assertTrue(context.actions().isEmpty());
    }

    @Test
    void evaluationEvidenceNumericActualValue_remainsNumeric() {
        UUID matchId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        RuleMatchEntity match = sampleMatch(matchId, eventId);

        when(ruleMatchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(domainEventRepository.findById(eventId)).thenReturn(Optional.empty());
        when(actionHistoryRepository.findByMatchId(matchId)).thenReturn(List.of());

        RuleMatchExplanationContext context = builder.build(matchId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evaluatedConditions =
                (List<Map<String, Object>>) context.evaluationEvidence().get("evaluatedConditions");
        Object actualValue = evaluatedConditions.get(0).get("actualValue");
        assertTrue(actualValue instanceof Number);
        assertEquals(34.7, ((Number) actualValue).doubleValue());
    }

    @Test
    void unknownMatchId_throwsResourceNotFoundException() {
        UUID matchId = UUID.randomUUID();
        when(ruleMatchRepository.findById(matchId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> builder.build(matchId));
    }

    @Test
    void exactlyOneLookupPerDependency() {
        UUID matchId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        RuleMatchEntity match = sampleMatch(matchId, eventId);

        when(ruleMatchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(domainEventRepository.findById(eventId)).thenReturn(Optional.empty());
        when(actionHistoryRepository.findByMatchId(matchId)).thenReturn(List.of());

        builder.build(matchId);

        verify(ruleMatchRepository, times(1)).findById(matchId);
        verify(domainEventRepository, times(1)).findById(eventId);
        verify(actionHistoryRepository, times(1)).findByMatchId(matchId);
    }
}
