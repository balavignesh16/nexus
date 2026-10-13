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
    Long expectedRevision
) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectFutureConfiguration(String key,Object value) {
        if(java.util.Set.of("triggers","trigger","scope","repeatPolicy","temporal","actionPolicy","approvalPolicy").contains(key))
            throw new IllegalArgumentException("Unsupported foundation configuration: "+key);
    }
    public RuleRequest(String name,boolean enabled,int priority,List<RuleCondition> conditions,List<RuleAction> actions,Map<String,String> metadata,Integer cooldown){this(name,enabled,priority,conditions,actions,metadata,cooldown,null);}
    public RuleRequest(String name, boolean enabled, int priority,
                        List<RuleCondition> conditions, List<RuleAction> actions, Map<String, String> metadata) {
        this(name, enabled, priority, conditions, actions, metadata, null);
    }
}
