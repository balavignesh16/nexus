package com.nexus.rule.api.dto;

import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RuleResponse(
    UUID ruleId,
    String name,
    boolean enabled,
    int priority,
    List<RuleCondition> conditions,
    List<RuleAction> actions,
    Map<String, String> metadata,
    Integer cooldownSeconds,
    String lifecycle,
    UUID activeVersionId,
    UUID workingVersionId,
    String workingVersionState,
    Long rowRevision,
    Long executionEpoch,
    String reconciliation,
    List<Map<String,Object>> diagnostics,
    Boolean originalEnabled,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<com.nexus.rule.domain.TriggerSubscription> triggers,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) com.nexus.rule.domain.RepeatPolicy repeatPolicy,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) com.nexus.rule.domain.ScheduleSpec schedule
) {
    public RuleResponse(UUID ruleId,String name,boolean enabled,int priority,List<RuleCondition> conditions,List<RuleAction> actions,Map<String,String> metadata,
            Integer cooldownSeconds,String lifecycle,UUID activeVersionId,UUID workingVersionId,String workingVersionState,Long rowRevision,Long executionEpoch,
            String reconciliation,List<Map<String,Object>> diagnostics,Boolean originalEnabled){
        this(ruleId,name,enabled,priority,conditions,actions,metadata,cooldownSeconds,lifecycle,activeVersionId,workingVersionId,workingVersionState,rowRevision,executionEpoch,reconciliation,diagnostics,originalEnabled,null,null,null);
    }
    public RuleResponse(UUID id,String name,boolean enabled,int priority,List<RuleCondition> conditions,List<RuleAction> actions,Map<String,String> metadata,Integer cooldown) {
        this(id,name,enabled,priority,conditions,actions,metadata,cooldown,null,null,null,null,null,null,null,null,null,null,null,null);
    }
    private static List<com.nexus.rule.domain.TriggerSubscription> triggers(Rule rule) {
        return rule.runtime() == null || rule.runtime().triggers().isEmpty() ? null : rule.runtime().triggers();
    }
    public static RuleResponse from(com.nexus.rule.persistence.RuleVersionStore.View view) {
        Rule r=view.definition();
        return new RuleResponse(r.ruleId(),r.name(),r.enabled(),r.priority(),r.conditions(),r.actions(),r.metadata(),r.cooldownSeconds(),
            view.lifecycle(),view.activeVersionId(),view.workingVersionId(),view.workingState(),view.rowRevision(),view.executionEpoch(),view.reconciliation(),view.diagnostics(),view.originalEnabled(),triggers(r),r.runtime()==null?null:r.runtime().repeatPolicy(),r.runtime()==null?null:r.runtime().schedule());
    }
    public static RuleResponse from(Rule rule) {
        return new RuleResponse(
                rule.ruleId(),
                rule.name(),
                rule.enabled(),
                rule.priority(),
                rule.conditions(),
                rule.actions(),
                rule.metadata(),
                rule.cooldownSeconds(),
                null,null,null,null,null,null,null,null,null,
                triggers(rule),rule.runtime()==null?null:rule.runtime().repeatPolicy(),rule.runtime()==null?null:rule.runtime().schedule()
        );
    }
}
