package com.nexus.rule.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.rule.domain.ConditionEvidence;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.rule.domain.RuleMatchedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
 * transaction. Twin events are intentionally published after the telemetry
 * transaction commits, so this write must own a new transaction rather than
 * joining the completed transaction synchronization.
 */
@Component
public class RuleMatchPersister {
    private org.springframework.jdbc.core.JdbcTemplate ownership;
    @org.springframework.beans.factory.annotation.Autowired
    void setOwnership(org.springframework.jdbc.core.JdbcTemplate ownership){this.ownership=ownership;}

    private final RuleMatchRepository repository;
    private final ObjectMapper objectMapper;

    public RuleMatchPersister(RuleMatchRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean persist(RuleMatchedEvent event) {
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

        entity.attribute(result.rule().executionIdentity());
        var identity=result.rule().executionIdentity();
        if(ownership!=null && identity!=null) {
            // History may persist after a switch, but cannot poison restored cooldown.
            var roots=ownership.queryForList("SELECT lifecycle,active_version_id,execution_epoch FROM rules WHERE rule_id=? FOR SHARE",result.rule().ruleId());
            entity.setCooldownEligible(!roots.isEmpty()&&"ACTIVE".equals(roots.getFirst().get("lifecycle"))
                && identity.ruleVersionId().equals(roots.getFirst().get("active_version_id"))
                && identity.executionEpoch()==((Number)roots.getFirst().get("execution_epoch")).longValue());
        }
        repository.save(entity);
        return identity==null || entity.isCooldownEligible();
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
        if (conditionEvidence.capabilityKey() != null) {
            map.put("capabilityKey", conditionEvidence.capabilityKey());
            map.put("definitionId", conditionEvidence.definitionId());
            map.put("bindingId", conditionEvidence.bindingId());
            map.put("valueType", conditionEvidence.valueType());
            map.put("unit", conditionEvidence.unit());
        }
        if (conditionEvidence.stateQuality() != null) {
            map.put("quality", conditionEvidence.stateQuality());
            map.put("qualityReason", conditionEvidence.qualityReason());
            map.put("qualityEvaluatedAt", conditionEvidence.qualityEvaluatedAt());
            map.put("receivedAt", conditionEvidence.receivedAt());
            map.put("freshnessThresholdSeconds", conditionEvidence.freshnessThresholdSeconds());
            map.put("connectivity", conditionEvidence.connectivity());
        }
        return map;
    }
}
