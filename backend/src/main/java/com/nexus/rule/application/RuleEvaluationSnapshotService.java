package com.nexus.rule.application;

import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.definition.LegacyCapabilityDefinitionMapping;
import com.nexus.device.connectivity.DeviceConnectivity;
import com.nexus.device.connectivity.DeviceConnectivityRepository;
import com.nexus.rule.domain.*;
import com.nexus.rule.persistence.RuleRegistry;
import com.nexus.rule.snapshot.*;
import com.nexus.rule.temporal.TemporalRuntimeEvidence;
import com.nexus.rule.temporal.TemporalStateStore;
import com.nexus.rule.trigger.TriggerEligibility;
import com.nexus.rule.trigger.TriggerObservation;
import com.nexus.twin.application.TwinStateQualityEvaluator;
import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.DigitalTwinRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * Acquires one coherent, detached view of everything an event batch's rule evaluation reads:
 * eligible immutable versions and owners, persisted Twin rows, persisted connectivity, installed
 * contracts, freshness policy and temporal runtime rows. Invoked through the Spring proxy by RuleMatcher and the
 * temporal runtime; the REPEATABLE READ read-only transaction takes no row locks and ends before any match or effect.
 */
@Service
public class RuleEvaluationSnapshotService {
    private final RuleRegistry registry;
    private final RuleCapabilityValidator validator;
    private final DigitalTwinRepository twins;
    private final DeviceConnectivityRepository connectivity;
    private final TwinStateQualityEvaluator quality;
    private final TemporalStateStore temporalStore;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final com.nexus.rule.trigger.RuleSourceCurrentness currentness;
    private com.nexus.rule.persistence.RuleHysteresisStore hysteresisStore;
    @org.springframework.beans.factory.annotation.Autowired void setHysteresisStore(com.nexus.rule.persistence.RuleHysteresisStore store){this.hysteresisStore=store;}
    private volatile Consumer<String> readProbe;

    public RuleEvaluationSnapshotService(RuleRegistry registry,RuleCapabilityValidator validator,DigitalTwinRepository twins,
            DeviceConnectivityRepository connectivity,TwinStateQualityEvaluator quality,TemporalStateStore temporalStore,JdbcTemplate jdbc,Clock clock,
            com.nexus.rule.trigger.RuleSourceCurrentness currentness){
        this.registry=registry;this.validator=validator;this.twins=twins;this.connectivity=connectivity;
        this.quality=quality;this.temporalStore=temporalStore;this.jdbc=jdbc;this.clock=clock;this.currentness=currentness;
    }

    /** Deterministic test seam invoked after each read phase; never set in production. */
    public void setReadProbe(Consumer<String> probe){this.readProbe=probe;}
    private void phase(String name){var p=readProbe;if(p!=null)p.accept(name);}

    /** Evaluation snapshot: the active versions explicitly subscribed to (and addressed by) this trigger. */
    @Transactional(isolation=Isolation.REPEATABLE_READ,readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public RuleEvaluationSnapshot acquire(TriggerObservation event,Instant evaluationTime){
        String isolation=isolation();
        List<Rule> active=registry.listEnabledOrderedByPriority().stream().filter(r->TriggerEligibility.eligible(r,event)).toList();
        phase("VERSIONS");
        return build(active,event,evaluationTime,isolation,true);
    }

    /**
     * Temporal observation snapshot: active versions with a temporal condition scoped to the observed device, so the
     * runtime updates its state from the same coherent view a decision would use. {@code onlyRule} narrows to one rule.
     */
    @Transactional(isolation=Isolation.REPEATABLE_READ,readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public RuleEvaluationSnapshot acquireTemporal(TriggerObservation observed,UUID onlyRule,Instant evaluationTime){
        String isolation=isolation();
        List<Rule> active=registry.listEnabledOrderedByPriority().stream()
            .filter(r->onlyRule==null||onlyRule.equals(r.ruleId()))
            .filter(r->r.conditions().stream().anyMatch(c->c.temporal()!=null))
            .filter(r->observed.deviceId()==null||observed.deviceId().equals(scopeOf(r,observed)))
            .toList();
        phase("VERSIONS");
        return build(active,observed,evaluationTime,isolation,false);   // temporal observation applies late evidence by design: no currentness verdict
    }

    /**
     * Dry-run, CURRENT_STATE: the same coherent backend view the production path uses, over EXPLICIT immutable versions (which need not be
     * active). It deliberately opens NO transaction of its own: the dry-run service runs the whole request as ONE REPEATABLE READ read-only
     * transaction on ONE connection, and this method joins it, so the snapshot, the version lookup and the runtime-state reads all see the same
     * committed state and no second connection is ever requested. It fails closed outside such a transaction.
     */
    public RuleEvaluationSnapshot captureForDryRun(List<Rule> versions,TriggerObservation trigger,Instant evaluationTime){
        String isolation=requireDryRunTransaction();
        phase("VERSIONS");
        return build(versions,trigger,evaluationTime,isolation,false);
    }

    /** Both guards must hold or nothing is read: an ambient read-write or lower-isolation transaction would silently break the guarantees. */
    private String requireDryRunTransaction(){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
            ||!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("A dry-run snapshot requires the caller's read-only transaction");
        String isolation=isolation();
        if(!"repeatable read".equalsIgnoreCase(isolation))
            throw new IllegalStateException("A dry-run snapshot requires REPEATABLE READ, but the transaction is "+isolation);
        return isolation;
    }

    /** The reported state a SUPPLIED_STATE dry-run substitutes for the persisted rows; built after the installed contracts are known. */
    public record Supplied(Map<UUID,List<DigitalTwin>> twins,Map<UUID,CapturedConnectivity> connectivity,
            Map<String,TwinStateQualityEvaluator.Inputs> qualityInputs,Map<String,TemporalRuntimeEvidence> temporal,Map<String,Boolean> hysteresis){}

    /**
     * Dry-run, SUPPLIED_STATE: rule versions, scope and INSTALLED CONTRACTS are still resolved from the backend (read-only, in the caller's single
     * REPEATABLE READ transaction, see {@link #captureForDryRun}), while the Twin rows, connectivity, temporal evidence and hysteresis latches come
     * from the caller. The resulting snapshot is evaluated by the very same evaluator as a production snapshot.
     */
    public RuleEvaluationSnapshot captureSupplied(List<Rule> versions,TriggerObservation trigger,Instant evaluationTime,
            java.util.function.Function<Map<String,CapturedCapability>,Supplied> supplied){
        String isolation=requireDryRunTransaction();
        var cap=capture(versions,trigger,false);
        var state=supplied.apply(cap.capabilities());
        return new RuleEvaluationSnapshot(UUID.randomUUID(),clock.instant(),evaluationTime,isolation,cap.captured(),state.twins(),state.connectivity(),
            cap.capabilities(),cap.unresolved(),state.qualityInputs(),state.temporal(),Optional.empty(),state.hysteresis());
    }

    private String isolation(){
        // The first statement fixes the PostgreSQL snapshot; the proof is read from the same transaction.
        return jdbc.queryForObject("SELECT current_setting('transaction_isolation')",String.class);
    }

    /** Rules, scope devices, installed contracts and (optionally) temporal runtime rows captured from one coherent read. */
    private record Capture(List<CapturedRule> captured,Set<UUID> devices,Map<String,CapturedCapability> capabilities,Set<String> unresolved,
            Map<String,TemporalRuntimeEvidence> temporal){}

    private Capture capture(List<Rule> active,TriggerObservation event,boolean readTemporal){
        var captured=new ArrayList<CapturedRule>();
        var devices=new LinkedHashSet<UUID>();
        if(event.deviceId()!=null)devices.add(event.deviceId());
        var capabilities=new LinkedHashMap<String,CapturedCapability>();
        var unresolved=new LinkedHashSet<String>();
        var temporal=new LinkedHashMap<String,TemporalRuntimeEvidence>();
        for(Rule rule:active){
            UUID scope=scopeOf(rule,event);
            boolean explicit=rule.conditions()!=null&&rule.conditions().stream().anyMatch(c->c.field()==RuleField.DEVICE_ID);
            boolean missing=!explicit&&event.deviceId()==null;
            List<Map<String,Object>> diagnostics=List.of();
            if(!missing){
                Rule validationRule=rule;
                if(!explicit){
                    var conditions=new ArrayList<>(rule.conditions());
                    conditions.add(new RuleCondition(RuleField.DEVICE_ID,RuleOperator.EQ,event.deviceId().toString()));
                    validationRule=new Rule(rule.ruleId(),rule.name(),rule.enabled(),rule.priority(),conditions,rule.actions(),rule.metadata(),rule.cooldownSeconds(),rule.executionIdentity(),rule.runtime());
                }
                diagnostics=validator.inspect(validationRule);
            }
            if(scope!=null&&!missing){
                devices.add(scope);
                for(RuleCondition c:rule.conditions())captureCapability(scope,c,capabilities,unresolved);
                if(readTemporal&&rule.executionIdentity()!=null&&rule.conditions().stream().anyMatch(c->c.temporal()!=null))
                    for(var state:temporalStore.forVersion(rule.executionIdentity().ruleVersionId(),rule.executionIdentity().executionEpoch()))
                        temporal.put(RuleEvaluationSnapshot.temporalKey(state.ruleVersionId(),state.conditionId(),state.scopeDeviceId()),state);
            }
            captured.add(new CapturedRule(rule,scope,missing,diagnostics));
        }
        phase("CONTRACTS");
        return new Capture(captured,devices,capabilities,unresolved,temporal);
    }

    private RuleEvaluationSnapshot build(List<Rule> active,TriggerObservation event,Instant evaluationTime,String isolation,boolean judgeSource){
        var cap=capture(active,event,true);
        var captured=cap.captured();var devices=cap.devices();var capabilities=cap.capabilities();var unresolved=cap.unresolved();var temporal=cap.temporal();
        var twinRows=new LinkedHashMap<UUID,List<DigitalTwin>>();
        for(UUID device:devices)twinRows.put(device,List.copyOf(twins.findByDeviceId(device)));
        phase("TWINS");
        var links=new LinkedHashMap<UUID,CapturedConnectivity>();
        for(DeviceConnectivity row:connectivity.findAllById(devices)){
            long generation=jdbc.queryForObject("SELECT generation FROM device_connectivity WHERE device_id=?",Long.class,row.deviceId());
            links.put(row.deviceId(),new CapturedConnectivity(row.status().name(),row.lastChangedAt(),generation));
        }
        phase("CONNECTIVITY");
        var inputs=new LinkedHashMap<String,TwinStateQualityEvaluator.Inputs>();
        for(var entry:twinRows.entrySet())for(DigitalTwin twin:entry.getValue()){
            var link=links.get(entry.getKey());
            inputs.put(RuleEvaluationSnapshot.twinKey(twin),quality.liveInputs(twin,link==null?"UNKNOWN":link.status()));
        }
        phase("QUALITY");
        // The trigger's source identity is compared with the state captured ABOVE, in this same REPEATABLE READ transaction.
        var superseded=judgeSource&&event.typed()?currentness.againstSnapshot(event,twinRows,links):java.util.Optional.<String>empty();
        phase("CURRENTNESS");
        var latches=new LinkedHashMap<String,Boolean>();
        if(hysteresisStore!=null)for(CapturedRule c:captured){
            var identity=c.rule().executionIdentity();
            if(identity==null||c.scopeDeviceId()==null||c.rule().conditions()==null)continue;
            for(RuleCondition condition:c.rule().conditions())
                if(condition!=null&&condition.hysteresis()!=null&&condition.conditionId()!=null)
                    latches.put(RuleEvaluationSnapshot.hysteresisKey(c.rule().ruleId(),condition.conditionId(),c.scopeDeviceId()),
                        hysteresisStore.latched(c.rule().ruleId(),condition.conditionId(),c.scopeDeviceId(),identity.executionEpoch()));
        }
        return new RuleEvaluationSnapshot(UUID.randomUUID(),clock.instant(),evaluationTime,isolation,captured,twinRows,links,capabilities,unresolved,inputs,temporal,superseded,latches);
    }

    private void captureCapability(UUID scope,RuleCondition c,Map<String,CapturedCapability> out,Set<String> unresolved){
        if(c.field()==RuleField.DEVICE_ID)return;
        String identifier=c.capabilityKey()!=null?c.capabilityKey():c.capabilityType()!=null?LegacyCapabilityDefinitionMapping.keyFor(c.capabilityType()):null;
        if(identifier==null)return;
        String key=RuleEvaluationSnapshot.capabilityKey(scope,identifier,CapabilityDirection.READ.name());
        if(out.containsKey(key)||unresolved.contains(key))return;
        try{out.put(key,CapturedCapability.of(validator.resolveInstalled(scope,identifier,CapabilityDirection.READ)));}
        catch(org.springframework.dao.DataAccessException|jakarta.persistence.PersistenceException infrastructure){throw infrastructure;}
        catch(RuntimeException invalid){unresolved.add(key);}
    }

    static UUID scopeOf(Rule rule,TriggerObservation event){
        if(rule.conditions()!=null)for(RuleCondition c:rule.conditions())
            if(c.field()==RuleField.DEVICE_ID&&c.expectedValue()!=null){
                try{return UUID.fromString(c.expectedValue());}catch(IllegalArgumentException e){return null;}
            }
        return event.deviceId();
    }
}
