package com.nexus.rule.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.rule.domain.ConditionEvidence;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.rule.domain.RuleMatchedEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durably persists every successful rule match (Tier 3 Phase 9B). Mirrors
 * the existing DomainEventPersister/ActionHistoryPersister shape: a single
 * public method, a constructor-injected repository, no internal try/catch
 * (a persistence failure must propagate so RuleMatcher's existing per-rule
 * try/catch can stop that match from reaching the cache, realtime, or
 * action planning - see RuleMatcher.onEvent()), and no transaction
 * annotation, since this is a single repository.save() call exactly like
 * every other persister in this codebase.
 */
@Component
public class RuleMatchPersister {

    private final RuleMatchRepository repository;
    private final ObjectMapper objectMapper;

    public RuleMatchPersister(RuleMatchRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void persist(RuleMatchedEvent event) {
        RuleEvaluationResult result = event.result();

        @SuppressWarnings("unchecked")
        Map<String, Object> ruleSnapshot = objectMapper.convertValue(result.rule(), Map.class);

        RuleMatchEntity entity = new RuleMatchEntity(
                event.matchId(),
                result.rule().ruleId(),
                ruleSnapshot,
                result.event().eventId(),
                event.correlationId(),
                buildEvaluationEvidence(result),
                event.timestamp(),
                result.message()
        );

        repository.save(entity);
    }

    private Map<String, Object> buildEvaluationEvidence(RuleEvaluationResult result) {
        List<Map<String, Object>> evaluatedConditions = result.evidence().stream()
                .map(this::toEvidenceMap)
                .toList();

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evaluatedConditions", evaluatedConditions);
        evidence.put("outcome", result.matched() ? "matched" : "not_matched");
        return evidence;
    }

    private Map<String, Object> toEvidenceMap(ConditionEvidence conditionEvidence) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("field", conditionEvidence.field().name());
        map.put("operator", conditionEvidence.operator().name());
        map.put("expectedValue", conditionEvidence.expectedValue());
        map.put("actualValue", conditionEvidence.actualValue());
        map.put("conditionMet", conditionEvidence.conditionMet());
        if (conditionEvidence.stateTimestamp() != null) {
            map.put("stateTimestamp", conditionEvidence.stateTimestamp().toString());
        }
        return map;
    }
}
