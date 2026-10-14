package com.nexus.rule.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

/** Persists one arbitrated plan per source fact in its own transaction; a duplicate fact decision is rejected. */
@Repository
public class RuleActionPlanStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    public RuleActionPlanStore(JdbcTemplate jdbc,ObjectMapper mapper,Clock clock){this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;}

    /** Returns the new plan id, or empty when this fact already has a plan (another worker decided it). */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Optional<UUID> persist(UUID factId,List<ActionDecision> decisions){
        UUID planId=UUID.randomUUID();
        int inserted=jdbc.update("INSERT INTO rule_action_plans(plan_id,fact_id,decided_at,status) VALUES(?,?,?,?) ON CONFLICT (fact_id) DO NOTHING",
            planId,factId,Timestamp.from(clock.instant()),decisions.isEmpty()?"NO_CANDIDATES":"APPROVED");
        if(inserted==0)return Optional.empty();
        for(ActionDecision d:decisions){
            var c=d.candidate();
            String parameters;
            try{parameters=mapper.writeValueAsString(c.action().parameters()==null?Map.of():c.action().parameters());}
            catch(Exception e){throw new IllegalStateException("Unserializable action parameters",e);}
            jdbc.update("""
                INSERT INTO rule_action_candidates(candidate_id,plan_id,match_id,rule_id,rule_version_id,rule_revision,action_ordinal,action_type,
                    target_key,priority,request_id,disposition,reason,winner_candidate_id,parameters)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)""",
                c.candidateId(),planId,c.matchId(),c.rule().ruleId(),c.versionId(),c.revision(),c.ordinal(),c.action().actionType(),
                c.targetKey(),c.priority(),c.requestId(),d.disposition().name(),d.reason(),d.winnerCandidateId(),parameters);
        }
        return Optional.of(planId);
    }
}
