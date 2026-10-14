package com.nexus.rule.plan;

import com.nexus.action.application.ActionExecutionEngine;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionType;
import com.nexus.event.domain.DomainEvent;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleMatchedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * The single authority for the actions of EVERY persisted match - typed, scheduled, temporal, chained and legacy DomainEvent:
 * candidates -> deterministic arbitration -> persisted plan -> dispatch of SELECTED candidates only. The former independent
 * per-match legacy dispatch (the C13 foundation exception) no longer exists. A failed plan persistence dispatches nothing, and the
 * request that reaches guarded acceptance must equal the persisted SELECTED candidate (see RuleExecutionAuthorization).
 */
@Component
public class RuleActionPlanner {
    private static final Logger log=LoggerFactory.getLogger(RuleActionPlanner.class);
    private final RuleActionPlanStore store;
    private final ActionExecutionEngine engine;
    public RuleActionPlanner(RuleActionPlanStore store,ActionExecutionEngine engine){this.store=store;this.engine=engine;}

    /** Typed batch: the committed source fact the persisted matches belong to. */
    public void planAndDispatch(UUID factId,List<RuleMatchedEvent> persistedMatches){planAndDispatch(PlanSource.fact(factId),persistedMatches);}

    /**
     * @param source the committed fact or the persisted legacy event this batch of persisted matches belongs to
     * @return true when THIS call created the plan (and dispatched its SELECTED candidates); false when the batch already had one
     */
    public boolean planAndDispatch(PlanSource source,List<RuleMatchedEvent> persistedMatches){
        var candidates=ActionCandidates.build(persistedMatches);
        var events=new HashMap<UUID,DomainEvent>();
        for(RuleMatchedEvent match:persistedMatches)
            if(match.result().rule().executionIdentity()!=null&&match.result().event()!=null)events.put(match.matchId(),match.result().event());
        Optional<RuleActionPlanStore.Plan> plan;
        try{plan=store.persist(source,candidates);}
        catch(RuntimeException failure){
            log.error("RULE_ACTION_PLAN_PERSISTENCE_FAILED {} - no action will be dispatched; the source stays recoverable",source,failure);
            // Surfaced, not swallowed: a typed fact is recorded FAILED by the router and rebuilt by the recovery scanner.
            throw failure;
        }
        if(plan.isEmpty()){log.info("RULE_ACTION_PLAN_ALREADY_DECIDED {}",source);return false;}
        for(ActionDecision decision:plan.get().decisions()){
            if(!decision.dispatches())continue;
            var c=decision.candidate();
            ActionType type;
            try{type=ActionType.valueOf(c.action().actionType().toUpperCase().replace("-","_"));}
            catch(IllegalArgumentException unknown){log.warn("Unknown ActionType requested: {}",c.action().actionType());continue;}
            var request=new ActionExecutionRequest(c.requestId(),c.matchId(),type,c.action().parameters());
            try{engine.process(List.of(request),new ActionContext(events.get(c.matchId()),c.rule()),c.correlationId());}
            catch(RuntimeException failure){log.error("Rule action failed request={}",c.requestId(),failure);}
        }
        try{store.recordOutcomes(plan.get().planId());}
        catch(RuntimeException bookkeeping){log.warn("RULE_PLAN_OUTCOME_NOT_RECORDED {}: {}",plan.get().planId(),bookkeeping.getMessage());}
        return true;
    }
}
