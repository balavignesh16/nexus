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
    public RuleExecutionAuthorization(JdbcTemplate jdbc) { this.jdbc=jdbc; }
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
        Integer evidence=jdbc.queryForObject("""
            SELECT count(*) FROM rule_matches m JOIN domain_events e ON e.event_id=m.event_id
            WHERE m.match_id=? AND m.rule_id=? AND m.rule_version_id=? AND m.rule_revision=? AND m.execution_epoch=?
            """,Integer.class,request.matchId(),rule.ruleId(),identity.ruleVersionId(),identity.ruleRevision(),identity.executionEpoch());
        if(evidence==null || evidence!=1)throw new RuleExecutionSuppressedException("DURABLE_SOURCE_MATCH_REQUIRED");
    }
    public boolean current(Rule rule) {
        var id=rule.executionIdentity();if(id==null)return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rules WHERE rule_id=? AND lifecycle='ACTIVE' AND active_version_id=? AND execution_epoch=? AND reconciliation IN ('PASSED','NOT_REQUIRED'))",Boolean.class,rule.ruleId(),id.ruleVersionId(),id.executionEpoch()));
    }
}
