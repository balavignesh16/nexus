package com.nexus.rule.persistence;

import com.nexus.rule.domain.Rule;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RuleRegistry {

    private final ConcurrentHashMap<UUID, Rule> rules = new ConcurrentHashMap<>();

    public void register(Rule rule) {
        validate(rule);
        rules.put(rule.ruleId(), rule);
    }

    public void unregister(UUID ruleId) {
        rules.remove(ruleId);
    }

    public void enable(UUID ruleId) {
        rules.computeIfPresent(ruleId, (id, r) -> new Rule(
                r.ruleId(), r.name(), true, r.priority(), r.conditions(), r.actions(), r.metadata()
        ));
    }

    public void disable(UUID ruleId) {
        rules.computeIfPresent(ruleId, (id, r) -> new Rule(
                r.ruleId(), r.name(), false, r.priority(), r.conditions(), r.actions(), r.metadata()
        ));
    }

    public Optional<Rule> get(UUID ruleId) {
        return Optional.ofNullable(rules.get(ruleId));
    }

    public List<Rule> list() {
        return new ArrayList<>(rules.values());
    }

    public List<Rule> listEnabledOrderedByPriority() {
        return rules.values().stream()
                .filter(Rule::enabled)
                .sorted(Comparator.comparingInt(Rule::priority))
                .toList();
    }

    private void validate(Rule rule) {
        if (rule.ruleId() == null) {
            throw new IllegalArgumentException("Rule ID cannot be null");
        }
        if (!StringUtils.hasText(rule.name())) {
            throw new IllegalArgumentException("Rule name cannot be empty");
        }
        if (rule.conditions() == null || rule.conditions().isEmpty()) {
            throw new IllegalArgumentException("Rule must have at least one condition");
        }
        if (rule.actions() == null || rule.actions().isEmpty()) {
            throw new IllegalArgumentException("Rule must have at least one action");
        }
        rule.conditions().forEach(c -> {
            if (c.field() == null) throw new IllegalArgumentException("Condition field cannot be null");
            if (c.operator() == null) throw new IllegalArgumentException("Condition operator cannot be null");
            if (c.expectedValue() == null) throw new IllegalArgumentException("Condition expected value cannot be null");
        });
        rule.actions().forEach(a -> {
            if (!StringUtils.hasText(a.actionType())) throw new IllegalArgumentException("Action type cannot be empty");
        });
    }
}
