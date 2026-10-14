package com.nexus.rule.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.rule.domain.ConditionEvidence;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.rule.domain.RuleMatchedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.UUID;
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

    private com.nexus.rule.trigger.RuleTriggerFactRecorder chain;
    @org.springframework.beans.factory.annotation.Autowired
    void setChain(@org.springframework.context.annotation.Lazy com.nexus.rule.trigger.RuleTriggerFactRecorder chain){this.chain=chain;}
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
                result.event() == null ? null : result.event().eventId(),
                event.correlationId(),
                buildEvaluationEvidence(result),
                event.timestamp(),
                result.message()
        );

        entity.attribute(result.rule().executionIdentity());
        var trigger = result.context() == null ? null : result.context().trigger();
        if (trigger != null && trigger.typed()) entity.triggeredBy(trigger.type().name(), trigger.factId());
        var identity=result.rule().executionIdentity();
        if(ownership!=null && identity!=null) {
            // History may persist after a switch, but cannot poison restored cooldown.
            var roots=ownership.queryForList("SELECT lifecycle,active_version_id,execution_epoch FROM rules WHERE rule_id=? FOR SHARE",result.rule().ruleId());
            entity.setCooldownEligible(!roots.isEmpty()&&"ACTIVE".equals(roots.getFirst().get("lifecycle"))
                && identity.ruleVersionId().equals(roots.getFirst().get("active_version_id"))
                && identity.executionEpoch()==((Number)roots.getFirst().get("execution_epoch")).longValue());
        }
        repository.save(entity);
        // The latch commits with the match, so an edge cannot be consumed without its attributed match (or vice versa).
        if (ownership != null && identity != null && result.context() != null && result.rule().repeat() == com.nexus.rule.domain.RepeatPolicy.EDGE_TRUE
                && result.context().captured().scopeDeviceId() != null)
            ownership.update("INSERT INTO rule_repeat_state(rule_id,scope_device_id,generation,latched,updated_at) VALUES(?,?,?,true,now()) "
                + "ON CONFLICT (rule_id,scope_device_id) DO UPDATE SET generation=EXCLUDED.generation,latched=true,updated_at=now()",
                result.rule().ruleId(), result.context().captured().scopeDeviceId(), identity.executionEpoch());
        if (trigger != null && chain != null && identity != null) recordChainFact(event, result, trigger, identity);
        return identity==null || entity.isCooldownEligible();
    }

    private Map<String, Object> buildEvaluationEvidence(RuleEvaluationResult result) {
        List<Map<String, Object>> evaluatedConditions = result.evidence().stream()
                .map(this::toEvidenceMap)
                .toList();

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evaluatedConditions", evaluatedConditions);
        evidence.put("outcome", result.matched() ? "matched" : "not_matched");
        if (result.context() != null) evidence.put("frozenContext", frozenContext(result.context()));
        return evidence;
    }

    /**
     * Makes a persisted match available to explicit RULE_MATCHED subscribers (C17) in the same transaction as the
     * match. Depth is capped at 8 and a logical rule may not repeat in its own causation chain; excess work is
     * recorded as rejected evidence on the parent instead of a fact.
     */
    private void recordChainFact(RuleMatchedEvent event, RuleEvaluationResult result,
            com.nexus.rule.trigger.TriggerObservation trigger, com.nexus.rule.domain.RuleExecutionIdentity identity) {
        if (!chain.hasChainSubscribers()) return;
        repository.flush(); // the fact row references this match
        int depth = trigger.chainDepth() + 1;
        var causation = new java.util.ArrayList<>(trigger.causationRuleIds());
        UUID ruleId = result.rule().ruleId();
        if (depth > 8 || causation.contains(ruleId)) {
            org.slf4j.LoggerFactory.getLogger(RuleMatchPersister.class)
                .warn("RULE_CHAIN_REJECTED match={} rule={} depth={} cyclic={}", event.matchId(), ruleId, depth, causation.contains(ruleId));
            if (ownership != null)
                ownership.update("INSERT INTO rule_chain_rejections(rejection_id,parent_match_id,fact_id,rule_id,reason,chain_depth,causation_rule_ids,rejected_at) VALUES(?,?,?,?,?,?,?,now())",
                    UUID.randomUUID(), event.matchId(), trigger.factId(), ruleId, depth > 8 ? "MAX_DEPTH" : "CYCLE", Math.min(depth, 8), causation.toArray(UUID[]::new));
            return;
        }
        causation.add(ruleId);
        java.util.Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("parentRuleId", ruleId.toString());
        attributes.put("parentRuleVersionId", identity.ruleVersionId().toString());
        chain.recordAndRoute(new com.nexus.rule.trigger.RuleTriggerFactRecorder.Fact(
            com.nexus.rule.domain.TriggerType.RULE_MATCHED, event.matchId().toString(), trigger.deviceId(), event.timestamp(),
            java.util.Map.of("matchId", event.matchId().toString(), "parentTriggerType", trigger.type().name(),
                "parentSourceId", String.valueOf(trigger.sourceId())),
            attributes, event.correlationId(), event.matchId(), trigger.factId() != null ? trigger.factId() : trigger.sourceId(), depth, causation));
    }

    /** The persisted decision of this rule version for this source fact, if one exists (idempotent re-routing and plan recovery). */
    public java.util.Optional<UUID> existingMatch(UUID ruleId, UUID versionId, UUID factId) {
        if (ownership == null || factId == null) return java.util.Optional.empty();
        return ownership.queryForList("SELECT match_id FROM rule_matches WHERE rule_id=? AND rule_version_id=? AND trigger_fact_id=?", UUID.class, ruleId, versionId, factId)
            .stream().findFirst();
    }

    /** Evidence for a chain trigger that reached a rule already present in its causation chain. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordChainRejection(com.nexus.rule.trigger.TriggerObservation trigger, UUID ruleId, String reason) {
        if (ownership == null) return;
        ownership.update("INSERT INTO rule_chain_rejections(rejection_id,parent_match_id,fact_id,rule_id,reason,chain_depth,causation_rule_ids,rejected_at) VALUES(?,?,?,?,?,?,?,now())",
            UUID.randomUUID(), trigger.parentMatchId(), trigger.factId(), ruleId, reason, Math.min(trigger.chainDepth(), 8), trigger.causationRuleIds().toArray(UUID[]::new));
    }

    /** Truthful snapshot attribution; rows written before this existed simply lack the block. */
    private Map<String, Object> frozenContext(com.nexus.rule.snapshot.RuleEvaluationContext ctx) {
        var snapshot = ctx.snapshot();
        Map<String, Object> frozen = new LinkedHashMap<>();
        frozen.put("snapshotId", snapshot.snapshotId().toString());
        frozen.put("evaluationId", ctx.evaluationId().toString());
        frozen.put("isolation", snapshot.isolation());
        frozen.put("acquiredAt", snapshot.acquiredAt().toString());
        frozen.put("evaluationTime", snapshot.evaluationTime().toString());
        Map<String, Object> trigger = new LinkedHashMap<>();
        var observation = ctx.trigger();
        trigger.put("type", observation.type().name());
        if (observation.legacyEvent() != null) {
            trigger.put("eventId", observation.legacyEvent().eventId().toString());
            trigger.put("eventType", observation.legacyEvent().eventType().name());
        } else {
            trigger.put("factId", String.valueOf(observation.factId()));
            trigger.put("sourceRef", observation.sourceRef());
            trigger.put("attributes", observation.attributes());
            trigger.put("chainDepth", observation.chainDepth());
        }
        trigger.put("occurredAt", observation.occurredAt().toString());
        trigger.put("recordedAt", observation.recordedAt().toString());
        trigger.put("deviceId", observation.deviceId() == null ? null : observation.deviceId().toString());
        frozen.put("trigger", trigger);
        frozen.put("scopeDeviceId", ctx.captured().scopeDeviceId() == null ? null : ctx.captured().scopeDeviceId().toString());
        var link = ctx.captured().scopeDeviceId() == null ? null : snapshot.connectivity().get(ctx.captured().scopeDeviceId());
        Map<String, Object> connectivity = new LinkedHashMap<>();
        connectivity.put("status", link == null ? "UNKNOWN" : link.status());
        connectivity.put("lastChangedAt", link == null ? null : link.lastChangedAt().toString());
        frozen.put("connectivity", connectivity);
        return frozen;
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
