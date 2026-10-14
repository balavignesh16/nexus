package com.nexus.rule.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

/**
 * Decides and persists ONE plan per trigger batch (typed fact or legacy DomainEvent) in its own transaction.
 * <p>
 * Order inside the transaction: claim the batch (the unique plan row: a redelivered batch is reused, never re-decided), read the
 * mutual-exclusion membership of the candidate rules with the group rows held FOR SHARE (a configuration change cannot interleave with
 * this decision), arbitrate deterministically, then persist every candidate with its disposition, reason and evidence. Any failure rolls
 * the whole decision back, and an undecided batch dispatches nothing.
 */
@Repository
public class RuleActionPlanStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    public RuleActionPlanStore(JdbcTemplate jdbc,ObjectMapper mapper,Clock clock){this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;}

    /** The persisted plan with the exact decisions that were stored. */
    public record Plan(UUID planId,List<ActionDecision> decisions){}

    /** Returns the new plan, or empty when this batch already has one (another worker decided it). */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Optional<Plan> persist(PlanSource source,List<ActionCandidate> candidates){
        UUID planId=UUID.randomUUID();
        String status=candidates.isEmpty()?"NO_CANDIDATES":"APPROVED";
        int inserted=source.kind()==PlanSource.Kind.FACT
            ?jdbc.update("INSERT INTO rule_action_plans(plan_id,fact_id,decided_at,status) VALUES(?,?,?,?) ON CONFLICT (fact_id) DO NOTHING",
                planId,source.id(),Timestamp.from(clock.instant()),status)
            :jdbc.update("INSERT INTO rule_action_plans(plan_id,event_id,decided_at,status) VALUES(?,?,?,?) ON CONFLICT (event_id) WHERE event_id IS NOT NULL DO NOTHING",
                planId,source.id(),Timestamp.from(clock.instant()),status);
        if(inserted==0)return Optional.empty();
        var memberships=memberships(candidates);
        var decisions=ActionArbiter.arbitrate(candidates,memberships);
        var groupsByRule=new HashMap<UUID,List<Map<String,Object>>>();
        for(var m:memberships)groupsByRule.computeIfAbsent(m.ruleId(),r->new ArrayList<>()).add(new LinkedHashMap<>(Map.of("groupId",m.groupId().toString(),"rank",m.rank())));
        for(ActionDecision d:decisions){
            var c=d.candidate();
            String parameters,evidence;
            try{
                parameters=mapper.writeValueAsString(c.action().parameters()==null?Map.of():c.action().parameters());
                var facts=new LinkedHashMap<String,Object>();
                facts.put("source",source.kind().name()+":"+source.id());
                facts.put("priority",c.priority());
                facts.put("equivalenceKey",c.equivalenceKey());
                facts.put("repeat",c.rule().repeat().name());
                facts.put("cooldownScope",c.rule().cooldownScope().name());
                facts.put("exclusionGroups",groupsByRule.getOrDefault(c.rule().ruleId(),List.of()));
                facts.put("safetyOverride","UNSUPPORTED");
                facts.put("approval","UNSUPPORTED");
                evidence=mapper.writeValueAsString(facts);
            }catch(Exception e){throw new IllegalStateException("Unserializable action parameters",e);}
            jdbc.update("""
                INSERT INTO rule_action_candidates(candidate_id,plan_id,match_id,rule_id,rule_version_id,rule_revision,action_ordinal,action_type,
                    target_key,priority,request_id,disposition,reason,winner_candidate_id,parameters,evidence)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)""",
                c.candidateId(),planId,c.matchId(),c.rule().ruleId(),c.versionId(),c.revision(),c.ordinal(),c.action().actionType(),
                c.targetKey(),c.priority(),c.requestId(),d.disposition().name(),d.reason(),d.winnerCandidateId(),parameters,evidence);
        }
        return Optional.of(new Plan(planId,decisions));
    }

    /**
     * Memberships of the candidate rules. First the rows of every group involved are locked FOR SHARE (so a configuration change that
     * starts later waits for this decision, and one already in flight is waited for), then - in a NEW statement, hence a fresh snapshot -
     * the members are read, so the decision sees the latest committed configuration and holds it fixed until it commits.
     */
    private List<ExclusionMembership> memberships(List<ActionCandidate> candidates){
        var rules=candidates.stream().map(c->c.rule().ruleId()).distinct().toList();
        if(rules.isEmpty())return List.of();
        String marks=String.join(",",Collections.nCopies(rules.size(),"?"));
        jdbc.queryForList("""
            SELECT g.group_id FROM rule_exclusion_groups g
            WHERE g.group_id IN (SELECT m.group_id FROM rule_exclusion_members m WHERE m.rule_id IN (%s)) ORDER BY g.group_id FOR SHARE""".formatted(marks),rules.toArray());
        return jdbc.query("""
            SELECT m.group_id,m.rule_id,m.rank FROM rule_exclusion_members m WHERE m.rule_id IN (%s) ORDER BY m.group_id,m.rule_id""".formatted(marks),
            (rs,n)->new ExclusionMembership(rs.getObject("group_id",UUID.class),rs.getObject("rule_id",UUID.class),rs.getInt("rank")),rules.toArray());
    }

    /**
     * Bookkeeping only; it never decides, authorizes or dispatches anything. For each SELECTED candidate it records what the executor
     * evidence actually proves. An ActionHistory row alone proves nothing:
     * <ul>
     * <li>command: ACCEPTED only when its DESIRED_STATE_CHANGED transition is durable (guarded acceptance happened); a FAILURE row
     *     without that transition is a rejection before acceptance (EXECUTION_FAILED). Publication, ACK and convergence stay separate P2.4
     *     facts and are not inferred;</li>
     * <li>other actions: EXECUTED on SUCCESS, EXECUTION_FAILED on FAILURE, UNSUPPORTED, IGNORED, with the executor's message;</li>
     * <li>no row and no transition: nothing is recorded (unknown).</li>
     * </ul>
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void recordOutcomes(UUID planId){
        jdbc.update("""
            UPDATE rule_action_candidates c SET outcome=x.outcome,outcome_reason=x.reason,outcome_at=?
            FROM (
              SELECT k.candidate_id,
                CASE
                  WHEN k.action_type='SEND_COMMAND' AND EXISTS(SELECT 1 FROM twin_state_transitions t WHERE t.transition_type='DESIRED_STATE_CHANGED' AND t.command_id=k.request_id) THEN 'ACCEPTED'
                  WHEN k.action_type<>'SEND_COMMAND' AND h.status='SUCCESS' THEN 'EXECUTED'
                  WHEN h.status='FAILURE' THEN 'EXECUTION_FAILED'
                  WHEN h.status='UNSUPPORTED' THEN 'UNSUPPORTED'
                  WHEN h.status='IGNORED' THEN 'IGNORED'
                END AS outcome,
                CASE WHEN h.status IN ('FAILURE','UNSUPPORTED','IGNORED') THEN left(h.message,500) END AS reason
              FROM rule_action_candidates k LEFT JOIN action_history h ON h.request_id=k.request_id
              WHERE k.plan_id=? AND k.outcome IS NULL AND k.disposition='SELECTED') x
            WHERE c.candidate_id=x.candidate_id AND x.outcome IS NOT NULL""",Timestamp.from(clock.instant()),planId);
    }
}
