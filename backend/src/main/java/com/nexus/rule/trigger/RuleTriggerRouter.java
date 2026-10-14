package com.nexus.rule.trigger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.rule.application.RuleMatcher;
import com.nexus.rule.domain.TriggerType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Post-commit routing boundary for typed facts. Loads the committed row (never an in-memory broadcast),
 * builds the trigger observation and hands it to the common evaluation pipeline.
 */
@Component
public class RuleTriggerRouter {
    private static final TypeReference<Map<String,Object>> MAP=new TypeReference<>(){};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RuleMatcher matcher;

    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RuleTriggerRouter.class);
    /** Bounded single-worker queue for chained (RULE_MATCHED) work; overflow is dropped with a log, never run inline. */
    private final java.util.concurrent.ThreadPoolExecutor queue=new java.util.concurrent.ThreadPoolExecutor(1,1,0L,
        java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(1000),
        runnable->{var t=new Thread(runnable,"rule-chain-router");t.setDaemon(true);return t;},
        new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    private final com.nexus.rule.temporal.RuleTemporalRuntime temporal;
    private final RuleFactDisposition disposition;
    private final RuleRecoveryEligibility eligibility;

    public RuleTriggerRouter(JdbcTemplate jdbc,ObjectMapper mapper,RuleMatcher matcher,com.nexus.rule.temporal.RuleTemporalRuntime temporal,RuleFactDisposition disposition,RuleRecoveryEligibility eligibility){
        this.jdbc=jdbc;this.mapper=mapper;this.matcher=matcher;this.temporal=temporal;this.disposition=disposition;this.eligibility=eligibility;
    }

    @jakarta.annotation.PreDestroy void shutdown(){queue.shutdownNow();}

    /** A full chain queue never drops silently: the committed fact stays PENDING with the reason, and the recovery scanner routes it. */
    public void routeQueued(UUID factId){
        try{queue.execute(()->{try{route(factId);}catch(RuntimeException failure){log.error("RULE_CHAIN_ROUTING_FAILED {}",factId,failure);}});}
        catch(java.util.concurrent.RejectedExecutionException full){
            log.error("RULE_CHAIN_QUEUE_FULL {} - fact stays PENDING for the recovery scanner",factId);
            disposition.deferred(factId,"CHAIN_QUEUE_FULL");
        }
    }

    /** Test/diagnostic barrier: returns once all currently queued chain work has finished. */
    public void awaitQueueIdle(long timeoutMillis) throws Exception {
        queue.submit(()->{}).get(timeoutMillis,java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Routes one committed fact and records the outcome durably. Temporal observation and rule evaluation are independent
     * steps: a failure in either marks the fact FAILED (bounded retry, then QUARANTINED) instead of being swallowed, and a retry
     * repeats only the missing part (temporal_applied_at; unique rule/version/fact decisions; one plan per fact).
     * @return false when the fact is not visible; true when both steps completed.
     */
    public boolean route(UUID factId){return route(factId,false);}

    /**
     * @param recovery true when the recovery scanner (not the inline post-commit path) routes the fact. A recovered fact is evaluated
     *     against CURRENT state, so it must first prove it still describes that state (see {@link RuleRecoveryEligibility}); otherwise
     *     it is durably EXPIRED without any rule match, plan or command. Temporal evidence is applied either way.
     */
    public boolean route(UUID factId,boolean recovery){
        var observation=load(factId);
        if(observation.isEmpty())return false;
        var fact=observation.get();
        disposition.attempt(factId);
        RuntimeException failure=null;
        try{
            if(!disposition.temporalApplied(factId)){
                temporal.observe(fact);
                disposition.markTemporalApplied(factId);
            }
        }catch(RuntimeException e){failure=e;log.error("RULE_TEMPORAL_OBSERVE_FAILED {}",factId,e);}
        java.util.Optional<String> expiry=java.util.Optional.empty();
        java.util.function.Predicate<com.nexus.rule.domain.Rule> admitted=rule->true;
        var skipped=new java.util.concurrent.CopyOnWriteArrayList<UUID>();
        if(recovery){
            expiry=eligibility.expiry(fact);
            if(expiry.isEmpty()){
                var authority=eligibility.recordedAuthority(factId);
                if(authority.isEmpty())expiry=java.util.Optional.of("RULE_AUTHORITY_NOT_RECORDED");
                else{
                    // Only the exact versions/epochs that were active when the fact was recorded may act on it: a version activated,
                    // resumed or replaced afterwards must not turn an older event into new intent.
                    var recorded=authority.get();
                    admitted=rule->{
                        var identity=rule.executionIdentity();
                        boolean ok=identity!=null&&RuleRecoveryEligibility.admits(recorded,rule.ruleId(),identity.ruleVersionId(),identity.executionEpoch());
                        if(!ok)skipped.add(rule.ruleId());
                        return ok;
                    };
                }
            }
        }
        if(expiry.isPresent()&&failure==null){disposition.expired(factId,expiry.get(),fact.occurredAt(),fact.recordedAt());return true;}
        if(expiry.isEmpty()){      // an ineligible fact whose temporal part failed is retried (and re-judged) without ever being matched
            try{expiry=matcher.processFact(fact,admitted);}      // present = the evaluation snapshot proved the source was superseded
            catch(RuntimeException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            if(expiry.isPresent()&&failure==null){disposition.expired(factId,expiry.get(),fact.occurredAt(),fact.recordedAt());return true;}
        }
        if(failure==null){if(skipped.isEmpty())disposition.routed(factId);else disposition.routedWithNote(factId,"RECOVERY_RULES_NOT_ADMITTED(authority changed since recording): "+skipped);return true;}
        boolean quarantined=disposition.failed(factId,failure);
        if(quarantined&&fact.deviceId()!=null&&(fact.type()==TriggerType.ACCEPTED_TELEMETRY||fact.type()==TriggerType.CONNECTIVITY_CHANGED))
            temporal.invalidateDevice(fact.deviceId(),"OBSERVATION_UNAVAILABLE");   // an unobservable fact must not leave false continuity behind
        throw failure;
    }

    public Optional<TriggerObservation> load(UUID factId){
        return jdbc.query("""
            SELECT fact_id,trigger_type,device_id,occurred_at,recorded_at,source_ref::text,attributes::text,correlation_id,
                   parent_match_id,chain_depth,causation_rule_ids
            FROM rule_trigger_facts WHERE fact_id=?""",(rs,n)->{
                try{
                    var ids=new ArrayList<UUID>();
                    var array=rs.getArray("causation_rule_ids");
                    if(array!=null)for(Object o:(Object[])array.getArray())ids.add((UUID)o);
                    return new TriggerObservation(TriggerType.valueOf(rs.getString("trigger_type")),factId,factId,null,
                        rs.getObject("device_id",UUID.class),rs.getTimestamp("occurred_at").toInstant(),rs.getTimestamp("recorded_at").toInstant(),
                        mapper.readValue(rs.getString("source_ref"),MAP),mapper.readValue(rs.getString("attributes"),MAP),
                        Optional.ofNullable(rs.getString("correlation_id")).orElse("fact:"+factId),rs.getObject("parent_match_id",UUID.class),rs.getInt("chain_depth"),ids);
                }catch(java.io.IOException e){throw new IllegalStateException("Unreadable source fact "+factId,e);}
            },factId).stream().findFirst();
    }
}
