package com.nexus.rule.persistence;

import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence-only JPA mapping for a {@link Rule}. Deliberately a separate
 * class rather than making {@code Rule} itself an {@code @Entity}: {@code
 * Rule} is a Java record (immutable, no no-arg constructor), which JPA
 * cannot proxy/instantiate the way it can a plain class - exactly the same
 * reason {@code DigitalTwin} is a hand-written class rather than a record.
 * Confining the JPA concern here keeps {@code Rule} and every one of its
 * existing consumers (RuleController, RuleMatcher, RuleEvaluator,
 * ActionPlanner, tests) completely unchanged (Tier 3 Phase 7).
 */
@Entity
@Table(name = "rules")
public class RuleEntity {

    @Id
    @Column(name = "rule_id")
    private UUID ruleId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "priority", nullable = false)
    private int priority;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conditions", nullable = false, columnDefinition = "jsonb")
    private List<RuleCondition> conditions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actions", nullable = false, columnDefinition = "jsonb")
    private List<RuleAction> actions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, String> metadata;

    // Phase 13: nullable - null means no cooldown (every pre-Phase-13 rule,
    // and any new rule that doesn't set one), matching Rule.cooldownSeconds.
    @Column(name = "cooldown_seconds")
    private Integer cooldownSeconds;

    protected RuleEntity() {
    }

    public RuleEntity(UUID ruleId, String name, boolean enabled, int priority,
                       List<RuleCondition> conditions, List<RuleAction> actions, Map<String, String> metadata,
                       Integer cooldownSeconds) {
        this.ruleId = ruleId;
        this.name = name;
        this.enabled = enabled;
        this.priority = priority;
        this.conditions = conditions;
        this.actions = actions;
        this.metadata = metadata;
        this.cooldownSeconds = cooldownSeconds;
    }

    public static RuleEntity fromDomain(Rule rule) {
        return new RuleEntity(
                rule.ruleId(),
                rule.name(),
                rule.enabled(),
                rule.priority(),
                rule.conditions(),
                rule.actions(),
                rule.metadata(),
                rule.cooldownSeconds()
        );
    }

    public Rule toDomain() {
        return new Rule(ruleId, name, enabled, priority, conditions, actions, metadata, cooldownSeconds);
    }

    public UUID ruleId() {
        return ruleId;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPriority() {
        return priority;
    }

    public List<RuleCondition> getConditions() {
        return conditions;
    }

    public List<RuleAction> getActions() {
        return actions;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public Integer getCooldownSeconds() {
        return cooldownSeconds;
    }
}
