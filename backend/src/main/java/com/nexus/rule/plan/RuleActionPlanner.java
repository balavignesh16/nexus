package com.nexus.rule.plan;

import com.nexus.action.application.ActionExecutionEngine;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionType;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleMatchedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Authority for actions of typed, scheduled, temporal and chained matches: candidates -> deterministic
 * arbitration -> persisted plan -> dispatch of SELECTED candidates only. It never serves legacy DomainEvent
 * matches, which keep the C13 foundation exception in ActionPlanner. A failed plan persistence dispatches nothing.
 */
@Component
public class RuleActionPlanner {
    private static final Logger log=LoggerFactory.getLogger(RuleActionPlanner.class);
    private final RuleActionPlanStore store;
    private final ActionExecutionEngine engine;
    public RuleActionPlanner(RuleActionPlanStore store,ActionExecutionEngine engine){this.store=store;this.engine=engine;}

    /** @param factId the committed source fact this batch of persisted matches belongs to */
    public void planAndDispatch(UUID factId,List<RuleMatchedEvent> persistedMatches){
        var candidates=new ArrayList<ActionCandidate>();
        for(RuleMatchedEvent match:persistedMatches){
            Rule rule=match.result().rule();
            if(rule.actions()==null||rule.executionIdentity()==null)continue;
            for(int i=0;i<rule.actions().size();i++){
                RuleAction action=rule.actions().get(i);
                boolean command="SEND_COMMAND".equals(action.actionType());
                candidates.add(new ActionCandidate(UUID.randomUUID(),match.matchId(),rule,i,action,UUID.randomUUID(),
                    command?ActionArbiter.targetKey(action):null,ActionArbiter.equivalenceKey(action),rule.priority(),match.correlationId()));
            }
        }
        var decisions=ActionArbiter.arbitrate(candidates);
        Optional<UUID> plan;
        try{plan=store.persist(factId,decisions);}
        catch(RuntimeException failure){
            log.error("RULE_ACTION_PLAN_PERSISTENCE_FAILED {} - no action will be dispatched; the fact stays recoverable",factId,failure);
            // Surfaced, not swallowed: the router records the fact as FAILED and the recovery scanner rebuilds the plan from the persisted matches.
            throw failure;
        }
        if(plan.isEmpty()){log.info("RULE_ACTION_PLAN_ALREADY_DECIDED {}",factId);return;}
        for(ActionDecision decision:decisions){
            if(!decision.dispatches())continue;
            var c=decision.candidate();
            ActionType type;
            try{type=ActionType.valueOf(c.action().actionType().toUpperCase().replace("-","_"));}
            catch(IllegalArgumentException unknown){log.warn("Unknown ActionType requested: {}",c.action().actionType());continue;}
            var request=new ActionExecutionRequest(c.requestId(),c.matchId(),type,c.action().parameters());
            try{engine.process(List.of(request),new ActionContext(null,c.rule()),c.correlationId());}
            catch(RuntimeException failure){log.error("Typed rule action failed request={}",c.requestId(),failure);}
        }
    }
}
