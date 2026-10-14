package com.nexus.rule.application;

import com.nexus.action.domain.*;
import com.nexus.rule.domain.Rule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** Invoked inside the actual side-effect transaction after device/contract locks. */
@Service
public class RuleExecutionAuthorization {
    private final JdbcTemplate jdbc;
    private final com.nexus.rule.trigger.RuleSourceCurrentness currentness;
    public RuleExecutionAuthorization(JdbcTemplate jdbc,com.nexus.rule.trigger.RuleSourceCurrentness currentness) { this.jdbc=jdbc;this.currentness=currentness; }
    public void check(ActionExecutionRequest request,ActionContext context) {
        if(context==null || context.matchedRule()==null) {
            if(request.matchId()!=null)throw new RuleExecutionSuppressedException("RULE_IDENTITY_REQUIRED");
            return; // Manual commands do not need fabricated rule identity.
        }
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new RuleExecutionSuppressedException("AUTHORIZATION_TRANSACTION_REQUIRED");
        Rule rule=context.matchedRule();var identity=rule.executionIdentity();
        if(identity==null)throw new RuleExecutionSuppressedException("PINNED_IDENTITY_REQUIRED");
        var rows=jdbc.queryForList("SELECT lifecycle,active_version_id,execution_epoch,reconciliation FROM rules WHERE rule_id=? FOR UPDATE",rule.ruleId());
        if(rows.isEmpty())throw new RuleExecutionSuppressedException("RULE_UNAVAILABLE");
        var owner=rows.getFirst();
        if(!"ACTIVE".equals(owner.get("lifecycle")))throw new RuleExecutionSuppressedException("RULE_NOT_ACTIVE");
        if(!identity.ruleVersionId().equals(owner.get("active_version_id")) || identity.executionEpoch()!=((Number)owner.get("execution_epoch")).longValue())throw new RuleExecutionSuppressedException("STALE_VERSION_OR_EPOCH");
        if(!Set.of("PASSED","NOT_REQUIRED").contains(owner.get("reconciliation")))throw new RuleExecutionSuppressedException("RECONCILIATION_INELIGIBLE");
        // C13 is retired: EVERY rule action - typed or legacy DomainEvent - needs a persisted, approved plan that SELECTED exactly this
        // request for this pinned version and committed source. There is no source-only exception any more.
        Integer evidence=jdbc.queryForObject("""
            SELECT count(*) FROM rule_action_candidates c
            JOIN rule_action_plans p ON p.plan_id=c.plan_id
            JOIN rule_matches m ON m.match_id=c.match_id
             AND ((p.fact_id IS NOT NULL AND m.trigger_fact_id=p.fact_id) OR (p.event_id IS NOT NULL AND m.trigger_fact_id IS NULL AND m.event_id=p.event_id))
            WHERE c.request_id=? AND c.match_id=? AND c.rule_id=? AND c.rule_version_id=? AND c.rule_revision=?
              AND m.execution_epoch=? AND c.disposition='SELECTED' AND p.status='APPROVED'
            """,Integer.class,request.requestId(),request.matchId(),rule.ruleId(),identity.ruleVersionId(),identity.ruleRevision(),identity.executionEpoch());
        if(evidence==null || evidence!=1)throw new RuleExecutionSuppressedException("PERSISTED_PLAN_AUTHORITY_REQUIRED");
        requireRequestMatchesCandidate(request);
        requireSourceStillCurrent(request);
    }
    /**
     * A superseded source must not accept a command. This runs inside command acceptance, after the device row (FOR UPDATE), the
     * capability definitions and the rule row are locked, and before any history/desired/verification write. The deciding source
     * row (Twin row or connectivity row) is read FOR SHARE and held until this transaction ends: a newer observation updates that
     * row, so it either committed first (seen here, acceptance denied) or waits until this acceptance has committed (the acceptance
     * linearized first and stays valid history). Telemetry and connectivity writers never take the device or rule locks that this
     * transaction holds, so no lock inversion exists; the source row lock is taken last and only ever waits for a short writer.
     */
    private void requireSourceStillCurrent(ActionExecutionRequest request) {
        var facts=jdbc.queryForList("SELECT m.trigger_fact_id,m.event_id FROM rule_matches m WHERE m.match_id=?",request.matchId());
        if(facts.isEmpty())throw new RuleExecutionSuppressedException("PERSISTED_PLAN_AUTHORITY_REQUIRED");
        // A legacy DomainEvent is an immutable fact of its own: it has no "latest source" to be superseded by.
        if(facts.getFirst().get("trigger_fact_id")==null&&facts.getFirst().get("event_id")!=null)return;
        if(facts.getFirst().get("trigger_fact_id")==null)throw new RuleExecutionSuppressedException("PERSISTED_PLAN_AUTHORITY_REQUIRED");
        var superseded=currentness.ofPersistedFact((UUID)facts.getFirst().get("trigger_fact_id"),true);
        if(superseded.isPresent())throw new RuleExecutionSuppressedException("SOURCE_SUPERSEDED: "+superseded.get());
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper CANONICAL = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * A valid request id is not enough: the dispatched action type, target and parameters must equal what the plan persisted for
     * that candidate, compared as values (numbers by decimal value, objects by key set), so a reused id cannot carry another action.
     */
    private void requireRequestMatchesCandidate(ActionExecutionRequest request) {
        var rows=jdbc.queryForList("SELECT action_type,target_key,parameters::text AS parameters FROM rule_action_candidates WHERE request_id=?",request.requestId());
        if(rows.size()!=1)throw new RuleExecutionSuppressedException("PERSISTED_PLAN_AUTHORITY_REQUIRED");
        var candidate=rows.getFirst();
        String persistedType=String.valueOf(candidate.get("action_type")).toUpperCase(java.util.Locale.ROOT).replace("-","_");
        if(request.actionType()==null||!persistedType.equals(request.actionType().name()))throw new RuleExecutionSuppressedException("CANDIDATE_REQUEST_MISMATCH");
        try {
            var persisted=CANONICAL.readTree(String.valueOf(candidate.get("parameters")));
            var dispatched=CANONICAL.valueToTree(request.parameters()==null?Map.of():request.parameters());
            if(!canonicalEquals(persisted,dispatched))throw new RuleExecutionSuppressedException("CANDIDATE_REQUEST_MISMATCH");
        } catch(com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            throw new RuleExecutionSuppressedException("CANDIDATE_REQUEST_MISMATCH");
        }
        String dispatchedTarget=com.nexus.rule.plan.ActionArbiter.targetKey(new com.nexus.rule.domain.RuleAction(String.valueOf(candidate.get("action_type")),request.parameters()==null?Map.of():request.parameters()));
        if(!Objects.equals(dispatchedTarget,candidate.get("target_key")))throw new RuleExecutionSuppressedException("CANDIDATE_REQUEST_MISMATCH");
    }

    static boolean canonicalEquals(com.fasterxml.jackson.databind.JsonNode a, com.fasterxml.jackson.databind.JsonNode b) {
        if(a.isNumber()&&b.isNumber())return a.decimalValue().compareTo(b.decimalValue())==0;
        if(a.isObject()&&b.isObject()) {
            if(a.size()!=b.size())return false;
            var names=a.fieldNames();
            while(names.hasNext()){String name=names.next();if(!b.has(name)||!canonicalEquals(a.get(name),b.get(name)))return false;}
            return true;
        }
        if(a.isArray()&&b.isArray()) {
            if(a.size()!=b.size())return false;
            for(int i=0;i<a.size();i++)if(!canonicalEquals(a.get(i),b.get(i)))return false;
            return true;
        }
        return a.equals(b);
    }

    public boolean current(Rule rule) {
        var id=rule.executionIdentity();if(id==null)return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rules WHERE rule_id=? AND lifecycle='ACTIVE' AND active_version_id=? AND execution_epoch=? AND reconciliation IN ('PASSED','NOT_REQUIRED'))",Boolean.class,rule.ruleId(),id.ruleVersionId(),id.executionEpoch()));
    }
}
