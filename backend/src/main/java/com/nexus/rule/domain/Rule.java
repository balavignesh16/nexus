package com.nexus.rule.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 13: {@code cooldownSeconds} is a new, optional field - null on
 * every rule persisted before Phase 13 (and the default for any new rule
 * that doesn't set it), meaning "no cooldown, current behavior." When set,
 * RuleMatcher will not re-match this rule again until that many seconds
 * have passed since its last durably-persisted match.
 */
public record Rule(
    UUID ruleId,
    String name,
    boolean enabled,
    int priority,
    List<RuleCondition> conditions,
    List<RuleAction> actions,
    Map<String, String> metadata,
    Integer cooldownSeconds,
    RuleExecutionIdentity executionIdentity,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) RuleRuntime runtime
) {
    public Rule {
        if(conditions!=null)conditions=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(conditions));
        if(actions!=null)actions=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(actions));
        if(metadata!=null)metadata=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
    }
    public Rule(UUID ruleId, String name, boolean enabled, int priority,
                List<RuleCondition> conditions, List<RuleAction> actions, Map<String, String> metadata,
                Integer cooldownSeconds, RuleExecutionIdentity executionIdentity) {
        this(ruleId, name, enabled, priority, conditions, actions, metadata, cooldownSeconds, executionIdentity, null);
    }
    public Rule(UUID ruleId, String name, boolean enabled, int priority,
                List<RuleCondition> conditions, List<RuleAction> actions, Map<String, String> metadata,
                Integer cooldownSeconds) {
        this(ruleId, name, enabled, priority, conditions, actions, metadata, cooldownSeconds, null, null);
    }
    /** Explicit subscriptions of this version; an absent runtime means the legacy DomainEvent adapter only. */
    public List<TriggerSubscription> subscriptions(){
        return runtime==null||runtime.triggers().isEmpty()?List.of(new TriggerSubscription(TriggerType.LEGACY_DOMAIN_EVENT)):runtime.triggers();
    }
    public boolean subscribesTo(TriggerType type){return subscriptions().stream().anyMatch(s->s.type()==type);}
    public RepeatPolicy repeat(){return runtime==null||runtime.repeatPolicy()==null?RepeatPolicy.EVERY_TRIGGER_WHILE_TRUE:runtime.repeatPolicy();}
    /** Copy preserving every field, so reconstruction sites cannot silently drop Phase 3 configuration. */
    public Rule with(boolean enabled,List<RuleCondition> conditions,RuleExecutionIdentity identity){
        return new Rule(ruleId,name,enabled,priority,conditions,actions,metadata,cooldownSeconds,identity,runtime);
    }
    public Rule(UUID ruleId, String name, boolean enabled, int priority,
                List<RuleCondition> conditions, List<RuleAction> actions, Map<String, String> metadata) {
        this(ruleId, name, enabled, priority, conditions, actions, metadata, null);
    }
}
