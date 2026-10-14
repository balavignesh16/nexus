package com.nexus.rule.api.dto;

import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.Map;

public record RuleRequest(
    @NotBlank String name,
    boolean enabled,
    int priority,
    @NotEmpty List<RuleCondition> conditions,
    @NotEmpty List<RuleAction> actions,
    Map<String, String> metadata,
    @Positive Integer cooldownSeconds,
    Long expectedRevision,
    List<com.nexus.rule.domain.TriggerSubscription> triggers,
    com.nexus.rule.domain.RepeatPolicy repeatPolicy,
    com.nexus.rule.domain.ScheduleSpec schedule,
    com.nexus.rule.domain.CooldownScope cooldownScope
) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectFutureConfiguration(String key,Object value) {
        if(java.util.Set.of("trigger","scope","temporal","actionPolicy","approvalPolicy","safetyOverride","safetyPolicy","approval","approvalRequired","requiresApproval","manualApproval","override").contains(key))
            throw new IllegalArgumentException("Unsupported configuration: "+key);
    }
    public RuleRequest(String name,boolean enabled,int priority,List<RuleCondition> conditions,List<RuleAction> actions,Map<String,String> metadata,Integer cooldown,Long expectedRevision){this(name,enabled,priority,conditions,actions,metadata,cooldown,expectedRevision,null,null,null,null);}
    /** Null for a request that configures nothing beyond the legacy adapter. */
    public com.nexus.rule.domain.RuleRuntime runtime(){
        return triggers==null&&repeatPolicy==null&&schedule==null&&cooldownScope==null?null:new com.nexus.rule.domain.RuleRuntime(triggers,repeatPolicy,schedule,cooldownScope);
    }
    public RuleRequest(String name,boolean enabled,int priority,List<RuleCondition> conditions,List<RuleAction> actions,Map<String,String> metadata,Integer cooldown){this(name,enabled,priority,conditions,actions,metadata,cooldown,null);}
    public RuleRequest(String name, boolean enabled, int priority,
                        List<RuleCondition> conditions, List<RuleAction> actions, Map<String, String> metadata) {
        this(name, enabled, priority, conditions, actions, metadata, null);
    }
}
