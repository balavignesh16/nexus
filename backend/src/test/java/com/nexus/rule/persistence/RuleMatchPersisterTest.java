package com.nexus.rule.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.rule.domain.ConditionEvidence;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleMatchedEvent;
import com.nexus.rule.domain.RuleOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for RuleMatchPersister (Tier 3 Phase 9B): a mocked
 * RuleMatchRepository, a real ObjectMapper (so the rule-snapshot and
 * evaluation-evidence mapping is genuinely exercised, not stubbed away).
 * Uses genuinely non-trivial rule/action/evidence content throughout, not
 * empty maps, matching the discipline established in Phase 9A's own tests.
 */
class RuleMatchPersisterTest {

    private RuleMatchRepository repository;
    private RuleMatchPersister persister;

    @BeforeEach
    void setUp() {
        repository = mock(RuleMatchRepository.class);
        persister = new RuleMatchPersister(repository, new ObjectMapper());
    }

    private Rule richRule() {
        return new Rule(
                UUID.randomUUID(), "High Temperature Alert", true, 3,
                List.of(
                        new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"),
                        new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30.0")
                ),
                List.of(
                        new RuleAction("CREATE_ALERT", Map.of("channel", "ops", "retries", 3, "urgent", true)),
                        new RuleAction("WEBHOOK", Map.of("url", "https://example.invalid/hook"))
                ),
                Map.of("owner", "facilities-team", "environment", "production")
        );
    }

    private DomainEvent domainEvent() {
        return new DomainEvent(UUID.randomUUID(), EventType.TWIN_UPDATED, UUID.randomUUID(),
                Instant.now(), "test", Map.of("value", 34.7), "INFO", "corr-persister");
    }

    private RuleMatchedEvent matchedEvent(Rule rule, DomainEvent event, List<ConditionEvidence> evidence) {
        RuleEvaluationResult result = new RuleEvaluationResult(true, rule, event, "All conditions met", evidence);
        return new RuleMatchedEvent(UUID.randomUUID(), Instant.now(), result, event.correlationId());
    }

    private List<ConditionEvidence> richEvidence() {
        return List.of(
                new ConditionEvidence(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED", "TWIN_UPDATED", true),
                new ConditionEvidence(RuleField.VALUE, RuleOperator.GT, "30.0", 34.7, true)
        );
    }

    @Test
    void persist_savesToRepository() {
        Rule rule = richRule();
        DomainEvent event = domainEvent();
        RuleMatchedEvent match = matchedEvent(rule, event, richEvidence());

        persister.persist(match);

        verify(repository).save(any(RuleMatchEntity.class));
    }

    @Test
    void persist_matchIdIsCorrect() {
        Rule rule = richRule();
        DomainEvent event = domainEvent();
        RuleMatchedEvent match = matchedEvent(rule, event, richEvidence());

        persister.persist(match);

        assertEquals(match.matchId(), captureSaved().getMatchId());
    }

    @Test
    void persist_ruleIdIsCorrect() {
        Rule rule = richRule();
        DomainEvent event = domainEvent();
        RuleMatchedEvent match = matchedEvent(rule, event, richEvidence());

        persister.persist(match);

        assertEquals(rule.ruleId(), captureSaved().getRuleId());
    }

    @Test
    void persist_eventIdIsCorrect() {
        Rule rule = richRule();
        DomainEvent event = domainEvent();
        RuleMatchedEvent match = matchedEvent(rule, event, richEvidence());

        persister.persist(match);

        assertEquals(event.eventId(), captureSaved().getEventId());
    }

    @Test
    void persist_correlationIdIsCorrect() {
        Rule rule = richRule();
        DomainEvent event = domainEvent();
        RuleMatchedEvent match = matchedEvent(rule, event, richEvidence());

        persister.persist(match);

        assertEquals("corr-persister", captureSaved().getCorrelationId());
    }

    @Test
    void persist_matchedAtIsCorrect() {
        Rule rule = richRule();
        DomainEvent event = domainEvent();
        RuleMatchedEvent match = matchedEvent(rule, event, richEvidence());

        persister.persist(match);

        assertEquals(match.timestamp(), captureSaved().getMatchedAt());
    }

    @Test
    void persist_messageIsCorrect() {
        Rule rule = richRule();
        DomainEvent event = domainEvent();
        RuleMatchedEvent match = matchedEvent(rule, event, richEvidence());

        persister.persist(match);

        assertEquals("All conditions met", captureSaved().getMessage());
    }

    @Test
    void persist_ruleSnapshotContainsTheFullRule() {
        Rule rule = richRule();
        DomainEvent event = domainEvent();
        RuleMatchedEvent match = matchedEvent(rule, event, richEvidence());

        persister.persist(match);

        Map<String, Object> snapshot = captureSaved().getRuleSnapshot();
        assertEquals("High Temperature Alert", snapshot.get("name"));
        assertEquals(true, snapshot.get("enabled"));
        assertEquals(3, snapshot.get("priority"));
        assertEquals(2, ((List<?>) snapshot.get("conditions")).size());
        assertEquals(2, ((List<?>) snapshot.get("actions")).size());
    }

    @Test
    void persist_evaluationEvidenceContainsEvaluatedConditions() {
        Rule rule = richRule();
        DomainEvent event = domainEvent();
        RuleMatchedEvent match = matchedEvent(rule, event, richEvidence());

        persister.persist(match);

        Map<String, Object> evidence = captureSaved().getEvaluationEvidence();
        assertEquals("matched", evidence.get("outcome"));
        List<?> evaluatedConditions = (List<?>) evidence.get("evaluatedConditions");
        assertEquals(2, evaluatedConditions.size());
    }

    @Test
    void persist_evidenceContainsActualValues() {
        Rule rule = richRule();
        DomainEvent event = domainEvent();
        RuleMatchedEvent match = matchedEvent(rule, event, richEvidence());

        persister.persist(match);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evaluatedConditions =
                (List<Map<String, Object>>) captureSaved().getEvaluationEvidence().get("evaluatedConditions");
        assertEquals("TWIN_UPDATED", evaluatedConditions.get(0).get("actualValue"));
        assertEquals(34.7, evaluatedConditions.get(1).get("actualValue"));
        assertEquals(true, evaluatedConditions.get(1).get("conditionMet"));
        assertEquals("VALUE", evaluatedConditions.get(1).get("field"));
        assertEquals("GT", evaluatedConditions.get(1).get("operator"));
        assertEquals("30.0", evaluatedConditions.get(1).get("expectedValue"));
    }

    @Test
    void persist_multipleConditions_preservedInOrder() {
        Rule rule = richRule();
        DomainEvent event = domainEvent();
        List<ConditionEvidence> evidence = List.of(
                new ConditionEvidence(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED", "TWIN_UPDATED", true),
                new ConditionEvidence(RuleField.SEVERITY, RuleOperator.NE, "CRITICAL", "INFO", true),
                new ConditionEvidence(RuleField.VALUE, RuleOperator.GT, "30.0", 34.7, true)
        );
        RuleMatchedEvent match = matchedEvent(rule, event, evidence);

        persister.persist(match);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evaluatedConditions =
                (List<Map<String, Object>>) captureSaved().getEvaluationEvidence().get("evaluatedConditions");
        assertEquals(3, evaluatedConditions.size());
        assertEquals("EVENT_TYPE", evaluatedConditions.get(0).get("field"));
        assertEquals("SEVERITY", evaluatedConditions.get(1).get("field"));
        assertEquals("VALUE", evaluatedConditions.get(2).get("field"));
    }

    @Test
    void persist_nonEmptyActionParameters_surviveInRuleSnapshot() {
        Rule rule = richRule();
        DomainEvent event = domainEvent();
        RuleMatchedEvent match = matchedEvent(rule, event, richEvidence());

        persister.persist(match);

        List<?> actions = (List<?>) captureSaved().getRuleSnapshot().get("actions");
        @SuppressWarnings("unchecked")
        Map<String, Object> firstAction = (Map<String, Object>) actions.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) firstAction.get("parameters");
        assertEquals("ops", parameters.get("channel"));
        assertEquals(3, parameters.get("retries"));
        assertEquals(true, parameters.get("urgent"));
    }

    @Test
    void persist_nonEmptyMetadata_survives() {
        Rule rule = richRule();
        DomainEvent event = domainEvent();
        RuleMatchedEvent match = matchedEvent(rule, event, richEvidence());

        persister.persist(match);

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) captureSaved().getRuleSnapshot().get("metadata");
        assertEquals("facilities-team", metadata.get("owner"));
        assertEquals("production", metadata.get("environment"));
    }

    @Test
    void persist_repositoryFailure_propagates() {
        Rule rule = richRule();
        DomainEvent event = domainEvent();
        RuleMatchedEvent match = matchedEvent(rule, event, richEvidence());
        doThrow(new RuntimeException("simulated database failure")).when(repository).save(any());

        assertThrows(RuntimeException.class, () -> persister.persist(match));
    }

    private RuleMatchEntity captureSaved() {
        ArgumentCaptor<RuleMatchEntity> captor = ArgumentCaptor.forClass(RuleMatchEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
