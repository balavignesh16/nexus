package com.nexus.rule.application;

import com.nexus.rule.domain.*;
import com.nexus.rule.persistence.RuleVersionStore;
import com.nexus.shared.exception.ValidationException;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

/** Externally invoked transaction boundary. Devices -> contracts -> root -> workflow. */
@Service
public class RuleLifecycleService {
    private final JdbcTemplate jdbc;
    private final RuleVersionStore store;
    private final RuleCapabilityValidator validator;
    private final EntityManager entities;
    private final Clock clock;
    private org.springframework.context.ApplicationEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired
    void setEvents(org.springframework.context.ApplicationEventPublisher events){this.events=events;}
    private com.nexus.rule.timer.RuleTimerService timers;
    @org.springframework.beans.factory.annotation.Autowired
    void setTimers(@org.springframework.context.annotation.Lazy com.nexus.rule.timer.RuleTimerService timers){this.timers=timers;}
    private void changed(UUID id){
        // Same transaction as the ownership change: stale generations are invalidated and the schedule is armed atomically.
        if(timers!=null)timers.reconcile(id);
        if(events!=null)events.publishEvent(new RuleProjectionChanged(id,jdbc.queryForObject("SELECT row_revision FROM rules WHERE rule_id=?",Long.class,id)));
    }
    public RuleLifecycleService(JdbcTemplate jdbc,RuleVersionStore store,RuleCapabilityValidator validator,
            EntityManager entities,Clock clock) {
        this.jdbc=jdbc; this.store=store; this.validator=validator; this.entities=entities; this.clock=clock;
    }
    private void lockContracts(Collection<Rule> definitions) {
        Set<UUID> ids=new TreeSet<>(Comparator.comparing(UUID::toString));
        for(Rule rule:definitions) {
            boolean explicit=false;
            if(rule.conditions()!=null) for(var condition:rule.conditions()) if(condition!=null&&condition.field()==RuleField.DEVICE_ID) {
                try { ids.add(UUID.fromString(condition.expectedValue())); explicit=true; }
                catch(Exception ignored) { /* The validator supplies the structured error. */ }
            }
            // TRIGGER_DEVICE can refer to any installed device. Lock the concrete current set.
            if(!explicit) ids.addAll(jdbc.queryForList("SELECT id FROM devices ORDER BY id",UUID.class));
            if(rule.actions()!=null) for(var action:rule.actions()) if(action!=null&&action.parameters()!=null && action.parameters().get("deviceId")!=null) {
                try { ids.add(UUID.fromString(action.parameters().get("deviceId").toString())); }
                catch(Exception ignored) { }
            }
        }
        for(UUID id:ids) jdbc.queryForList("SELECT id FROM devices WHERE id=? FOR UPDATE",id);
        // Shared definition locks prevent concurrent structural mutation during validation.
        jdbc.queryForList("SELECT id FROM capability_definitions ORDER BY id FOR SHARE");
        entities.clear(); // Reload current contracts, never use pre-lock managed entities.
    }
    private Map<String,Object> lockRoot(UUID id,Long expected) {
        var rows=jdbc.queryForList("SELECT * FROM rules WHERE rule_id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new RuleMutationException(404,"Rule not found");
        var root=rows.getFirst();
        if("ARCHIVED".equals(root.get("lifecycle"))) throw new RuleMutationException(409,"Archived rule is terminal");
        if(expected!=null && ((Number)root.get("row_revision")).longValue()!=expected)
            throw new RuleMutationException(409,"Stale rule revision; reload explicitly");
        return root;
    }
    private UUID insertVersion(Rule rule,String provenance) {
        long revision=jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM rule_versions WHERE rule_id=?",Long.class,rule.ruleId());
        UUID id=UUID.randomUUID();
        Rule executable=new Rule(rule.ruleId(),rule.name(),false,rule.priority(),rule.conditions(),rule.actions(),rule.metadata(),rule.cooldownSeconds(),null,rule.runtime());
        jdbc.update("INSERT INTO rule_versions(version_id,rule_id,revision,definition,created_at,provenance) VALUES(?,?,?,?::jsonb,?,?)",
            id,rule.ruleId(),revision,store.json(executable),java.sql.Timestamp.from(clock.instant()),provenance);
        jdbc.update("INSERT INTO rule_version_workflow VALUES(?,'DRAFT')",id);
        return id;
    }
    private boolean validateVersion(UUID id,UUID version,String purpose) {
        var result=validator.inspect(store.version(id,version));
        jdbc.update("INSERT INTO rule_validation_attempts(attempt_id,rule_id,version_id,purpose,valid,diagnostics,validated_at) VALUES(?,?,?,?,?,?::jsonb,?)",
            UUID.randomUUID(),id,version,purpose,result.isEmpty(),store.json(result),java.sql.Timestamp.from(clock.instant()));
        if(result.isEmpty()) jdbc.update("UPDATE rule_version_workflow SET state='VALIDATED' WHERE version_id=?",version);
        return result.isEmpty();
    }
    private void requireValid(Rule rule){var errors=validator.inspect(rule);if(!errors.isEmpty())throw new RuleDefinitionValidationException(errors);}
    private void projection(UUID id,Rule rule) {
        String name=rule.name()==null||rule.name().isBlank()?"Incomplete draft":rule.name();
        jdbc.update("UPDATE rules SET name=?,priority=?,conditions=?::jsonb,actions=?::jsonb,metadata=?::jsonb,cooldown_seconds=? WHERE rule_id=?",
            name,rule.priority(),store.json(rule.conditions()),store.json(rule.actions()),store.json(rule.metadata()==null?Map.of():rule.metadata()),rule.cooldownSeconds(),id);
    }
    @Transactional
    public RuleVersionStore.View createDraft(Rule rule) {
        jdbc.update("INSERT INTO rules(rule_id,name,enabled,priority,conditions,actions,metadata) VALUES(?,'Incomplete draft',false,0,'[]','[]','{}')",rule.ruleId());
        UUID version=insertVersion(rule,"DRAFT_CREATE");
        projection(rule.ruleId(),rule);
        jdbc.update("UPDATE rules SET working_version_id=? WHERE rule_id=?",version,rule.ruleId());
        changed(rule.ruleId());return store.get(rule.ruleId()).orElseThrow();
    }
    @Transactional
    public RuleVersionStore.View legacySave(Rule rule,Long token) {
        var before=store.get(rule.ruleId());
        // Tokenless clients still pin the server-observed revision before the root lock.
        Long observed=token!=null?token:before.map(RuleVersionStore.View::rowRevision).orElse(null);
        lockContracts(before.isPresent()?List.of(rule,before.get().definition()):List.of(rule));
        UUID version;
        if(before.isEmpty()) {
            requireValid(rule);
            jdbc.update("INSERT INTO rules(rule_id,name,enabled,priority,conditions,actions,metadata,cooldown_seconds) VALUES(?,?,false,?,?::jsonb,?::jsonb,?::jsonb,?)",
                rule.ruleId(),rule.name(),rule.priority(),store.json(rule.conditions()),store.json(rule.actions()),store.json(rule.metadata()==null?Map.of():rule.metadata()),rule.cooldownSeconds());
            version=insertVersion(rule,"API_CREATE");
        } else {
            lockRoot(rule.ruleId(),observed);
            requireValid(rule);
            version=store.sameDefinition(before.get().definition(),rule)?before.get().workingVersionId():insertVersion(rule,"LEGACY_EDIT");
        }
        if(!validateVersion(rule.ruleId(),version,"LEGACY_SAVE")) throw new ValidationException("Rule validation failed");
        projection(rule.ruleId(),rule);
        jdbc.update("UPDATE rules SET working_version_id=?,active_version_id=CASE WHEN ? THEN ? ELSE active_version_id END,lifecycle=?,enabled=?,execution_epoch=execution_epoch+1,row_revision=row_revision+1,reconciliation='NOT_REQUIRED',restoration_eligible=false WHERE rule_id=?",
            version,rule.enabled(),version,rule.enabled()?"ACTIVE":"PAUSED",rule.enabled(),rule.ruleId());
        changed(rule.ruleId());
        return store.get(rule.ruleId()).orElseThrow();
    }
    @Transactional
    public RuleVersionStore.View draft(Rule rule,long token) {
        lockContracts(List.of(rule));
        var root=lockRoot(rule.ruleId(),token);
        UUID version=insertVersion(rule,"DRAFT_EDIT");
        projection(rule.ruleId(),rule);
        jdbc.update("UPDATE rules SET working_version_id=?,row_revision=row_revision+1,restoration_eligible=false,lifecycle=CASE WHEN lifecycle IN ('DRAFT','VALIDATED') THEN 'DRAFT' ELSE lifecycle END WHERE rule_id=?",version,rule.ruleId());
        changed(rule.ruleId());
        return store.get(rule.ruleId()).orElseThrow();
    }
    @Transactional
    public RuleVersionStore.View transition(UUID id,String operation,Long token) {
        if(jdbc.queryForList("SELECT lifecycle FROM rules WHERE rule_id=?",String.class,id).contains("ARCHIVED"))throw new RuleMutationException(409,"Archived rule is terminal");
        var observed=store.get(id).orElseThrow(()->new RuleMutationException(404,"Rule not found"));
        lockContracts(List.of(observed.definition()));
        var root=lockRoot(id,token!=null?token:observed.rowRevision());
        UUID version=(UUID)root.get("working_version_id");
        if("resume".equals(operation) && !"PAUSED".equals(root.get("lifecycle")))throw new RuleMutationException(409,"Resume requires PAUSED lifecycle");
        if("validate".equals(operation) || "activate".equals(operation) || "resume".equals(operation)) {
            boolean valid=validateVersion(id,version,operation.toUpperCase(Locale.ROOT));
            // Return durable failed validation evidence without partially changing ownership.
            if(!valid) return store.get(id).orElseThrow();
            if("validate".equals(operation)) {
                jdbc.update("UPDATE rules SET row_revision=row_revision+1,lifecycle=CASE WHEN lifecycle='DRAFT' THEN 'VALIDATED' ELSE lifecycle END WHERE rule_id=?",id);
            } else {
                jdbc.update("UPDATE rules SET active_version_id=?,enabled=true,lifecycle='ACTIVE',execution_epoch=execution_epoch+1,row_revision=row_revision+1,reconciliation='NOT_REQUIRED',restoration_eligible=false WHERE rule_id=?",version,id);
            }
        } else if("pause".equals(operation) || "archive".equals(operation)) {
            jdbc.update("UPDATE rules SET enabled=false,lifecycle=?,execution_epoch=execution_epoch+1,row_revision=row_revision+1,restoration_eligible=false WHERE rule_id=?","pause".equals(operation)?"PAUSED":"ARCHIVED",id);
            if("archive".equals(operation)){changed(id);return null;}
        } else throw new RuleMutationException(400,"Unsupported lifecycle operation");
        changed(id);
        return store.get(id).orElseThrow();
    }
    @Transactional
    public void reconcile(UUID id,long observedRevision) {
        var observed=store.get(id);
        if(observed.isEmpty() || !"PENDING".equals(observed.get().reconciliation())) return;
        lockContracts(List.of(observed.get().definition()));
        var root=lockRoot(id,null);
        if(!"PENDING".equals(root.get("reconciliation"))) return;
        if(((Number)root.get("row_revision")).longValue()!=observedRevision) return;
        UUID version=(UUID)root.get("working_version_id");
        boolean valid=validateVersion(id,version,"RECONCILIATION");
        boolean restore=valid && Boolean.TRUE.equals(root.get("original_enabled")) && Boolean.TRUE.equals(root.get("restoration_eligible"));
        String outcome=valid?"PASSED":validator.inspect(observed.get().definition()).stream().anyMatch(e->"AMBIGUOUS_SCOPE".equals(e.get("code")))?"REQUIRES_REVIEW":"FAILED";
        jdbc.update("UPDATE rules SET reconciliation=?,enabled=?,lifecycle=?,active_version_id=CASE WHEN ? THEN ? ELSE active_version_id END,execution_epoch=execution_epoch+CASE WHEN ? THEN 1 ELSE 0 END,row_revision=row_revision+1,restoration_eligible=false WHERE rule_id=?",
            outcome,restore,restore?"ACTIVE":"PAUSED",restore,version,restore,id);
        changed(id);
    }
}
