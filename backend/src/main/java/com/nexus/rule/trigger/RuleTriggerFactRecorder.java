package com.nexus.rule.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.rule.domain.TriggerType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Writes a typed source fact inside the caller's SOURCE transaction (MANDATORY) so the fact commits or rolls
 * back with the source change, then routes it only after that commit. Duplicate source identities are
 * deduplicated by the (trigger_type, source_key) constraint and are never routed twice.
 */
@Component
public class RuleTriggerFactRecorder {
    private static final Logger log=LoggerFactory.getLogger(RuleTriggerFactRecorder.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private RuleTriggerRouter router;

    public RuleTriggerFactRecorder(JdbcTemplate jdbc,ObjectMapper mapper,Clock clock){this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;}
    @Autowired void setRouter(@Lazy RuleTriggerRouter router){this.router=router;}

    public record Fact(TriggerType type,String sourceKey,UUID deviceId,Instant occurredAt,Map<String,Object> sourceRef,
            Map<String,Object> attributes,String correlationId,UUID parentMatchId,UUID rootFactId,int chainDepth,List<UUID> causationRuleIds) {
        public Fact(TriggerType type,String sourceKey,UUID deviceId,Instant occurredAt,Map<String,Object> sourceRef,Map<String,Object> attributes,String correlationId){
            this(type,sourceKey,deviceId,occurredAt,sourceRef,attributes,correlationId,null,null,0,List.of());
        }
    }

    /** Inserts the fact in the current transaction. Returns the new fact id, or empty for a duplicate source identity. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<UUID> record(Fact fact){
        if(fact.type()==TriggerType.LEGACY_DOMAIN_EVENT)throw new IllegalArgumentException("Legacy events are persisted as DomainEvents, not typed facts");
        UUID id=UUID.randomUUID();
        UUID[] causation=fact.causationRuleIds().toArray(UUID[]::new);
        var inserted=jdbc.query("""
            INSERT INTO rule_trigger_facts(fact_id,trigger_type,source_key,device_id,occurred_at,recorded_at,source_ref,attributes,correlation_id,
                parent_match_id,root_fact_id,chain_depth,causation_rule_ids,rule_authority)
            VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,
                (SELECT COALESCE(jsonb_object_agg(rule_id::text,jsonb_build_object('v',active_version_id::text,'e',execution_epoch)),'{}'::jsonb)
                   FROM rules WHERE lifecycle='ACTIVE' AND active_version_id IS NOT NULL))
            ON CONFLICT (trigger_type,source_key) DO NOTHING RETURNING fact_id""",
            (rs,n)->rs.getObject(1,UUID.class),id,fact.type().name(),fact.sourceKey(),fact.deviceId(),
            Timestamp.from(fact.occurredAt()),Timestamp.from(clock.instant()),json(fact.sourceRef()),json(fact.attributes()),
            fact.correlationId(),fact.parentMatchId(),fact.rootFactId(),fact.chainDepth(),causation);
        return inserted.stream().findFirst();
    }

    /** Records the fact and schedules routing after the surrounding source transaction commits. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<UUID> recordAndRoute(Fact fact){
        var id=record(fact);
        // Chained work goes through a bounded queue: never unbounded synchronous recursion under a rule lock.
        id.ifPresent(f->routeAfterCommit(f,fact.type()==TriggerType.RULE_MATCHED));
        return id;
    }

    /**
     * Whether any ACTIVE immutable version currently needs a fact of this type: an explicit subscription, or (for the telemetry and
     * connectivity streams) a temporal condition scoped to this device. Conservative on filters. When nothing needs the fact it is
     * not written, so idle subscriptions cost nothing; a subscription activated later never receives synthetic history.
     */
    public boolean needed(TriggerType type, UUID deviceId) {
        boolean temporalStream = type == TriggerType.ACCEPTED_TELEMETRY || type == TriggerType.CONNECTIVITY_CHANGED;
        String scope = deviceId == null ? "[]" : "[{\"field\":\"DEVICE_ID\",\"expectedValue\":\"" + deviceId + "\"}]";
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM rules r JOIN rule_versions v ON v.version_id=r.active_version_id
                WHERE r.lifecycle='ACTIVE' AND (
                    v.definition->'runtime'->'triggers' @> ?::jsonb
                    OR (? AND v.definition->'conditions' @> '[{"temporal":{}}]'::jsonb AND v.definition->'conditions' @> ?::jsonb)))""",
            Boolean.class, "[{\"type\":\"" + type.name() + "\"}]", temporalStream, scope));
    }

    /** True when an active immutable version explicitly subscribes to RULE_MATCHED. */
    public boolean hasChainSubscribers(){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM rules r JOIN rule_versions v ON v.version_id=r.active_version_id
                WHERE r.lifecycle='ACTIVE' AND v.definition->'runtime'->'triggers' @> '[{"type":"RULE_MATCHED"}]'::jsonb)""",Boolean.class));
    }

    public void routeAfterCommit(UUID factId){routeAfterCommit(factId,false);}
    public void routeAfterCommit(UUID factId,boolean queued){
        if(TransactionSynchronizationManager.isSynchronizationActive()){
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){safeRoute(factId,queued);}
            });
        } else safeRoute(factId,queued);
    }
    private void safeRoute(UUID factId,boolean queued){
        try{if(queued)router.routeQueued(factId);else router.route(factId);}
        // A routing failure never rolls back the committed source fact; there is no crash-recovery guarantee here.
        catch(RuntimeException failure){log.error("RULE_FACT_ROUTING_FAILED {}",factId,failure);}
    }
    private String json(Object value){
        try{return mapper.writeValueAsString(value==null?Map.of():value);}
        catch(Exception e){throw new IllegalArgumentException("Unserializable source fact",e);}
    }
}
