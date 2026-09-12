package com.nexus.rule.persistence;

import com.nexus.rule.domain.Rule;
import jakarta.annotation.PostConstruct;
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
    private final RuleRepository repository;

    public RuleRegistry(RuleRepository repository) {
        this.repository = repository;
    }

    /**
     * Hydrates the in-memory registry from the database at startup
     * (Tier 3 Phase 7). Deliberately has no try/catch: if the database
     * cannot be read, or a persisted rule cannot be deserialized, this
     * exception must propagate and fail application startup rather than
     * let NEXUS silently start with an incomplete or empty rule set -
     * mirroring InMemoryDigitalTwinRegistry.init()'s existing, already
     * fail-closed hydration behavior. An empty rules table is a distinct,
     * valid case (a fresh install) and correctly results in an empty
     * registry, not a failure.
     */
    @PostConstruct
    public void init() {
        for (RuleEntity entity : repository.findAll()) {
            rules.put(entity.ruleId(), entity.toDomain());
        }
    }

    public void register(Rule rule) {
        validate(rule);
        persistAndCache(rule);
    }

    public void unregister(UUID ruleId) {
        repository.deleteById(ruleId);
        rules.remove(ruleId);
    }

    public void enable(UUID ruleId) {
        Rule current = rules.get(ruleId);
        if (current == null) {
            return;
        }
        persistAndCache(new Rule(
                current.ruleId(), current.name(), true, current.priority(), current.conditions(), current.actions(), current.metadata()
        ));
    }

    public void disable(UUID ruleId) {
        Rule current = rules.get(ruleId);
        if (current == null) {
            return;
        }
        persistAndCache(new Rule(
                current.ruleId(), current.name(), false, current.priority(), current.conditions(), current.actions(), current.metadata()
        ));
    }

    /**
     * Persist-before-cache (Tier 3 Phase 7): the database write happens
     * first; only once it succeeds is the in-memory registry updated. If
     * repository.save() throws, it propagates to the caller and the cache
     * is left untouched - the two can never observably diverge as a result
     * of a failed write, since the cache write is unreachable in that case.
     */
    private void persistAndCache(Rule rule) {
        repository.save(RuleEntity.fromDomain(rule));
        rules.put(rule.ruleId(), rule);
    }

    public Optional<Rule> get(UUID ruleId) {
        return Optional.ofNullable(rules.get(ruleId));
    }

    public List<Rule> list() {
        return new ArrayList<>(rules.values());
    }

    public void clear() {
        rules.clear();
    }

    public List<Rule> listEnabledOrderedByPriority() {
        return rules.values().stream()
                .filter(Rule::enabled)
                .sorted(Comparator.comparingInt(Rule::priority))
                .toList();
    }

    private void validate(Rule rule) {
        if (rule.ruleId() == null) {
            throw new com.nexus.shared.exception.ValidationException("Rule ID cannot be null");
        }
        if (!StringUtils.hasText(rule.name())) {
            throw new com.nexus.shared.exception.ValidationException("Rule name cannot be empty");
        }
        if (rule.conditions() == null || rule.conditions().isEmpty()) {
            throw new com.nexus.shared.exception.ValidationException("Rule must have at least one condition");
        }
        if (rule.actions() == null || rule.actions().isEmpty()) {
            throw new com.nexus.shared.exception.ValidationException("Rule must have at least one action");
        }
        rule.conditions().forEach(c -> {
            if (c.field() == null) throw new com.nexus.shared.exception.ValidationException("Condition field cannot be null");
            if (c.operator() == null) throw new com.nexus.shared.exception.ValidationException("Condition operator cannot be null");
            if (c.expectedValue() == null) throw new com.nexus.shared.exception.ValidationException("Condition expected value cannot be null");
        });
        rule.actions().forEach(a -> {
            if (!StringUtils.hasText(a.actionType())) throw new com.nexus.shared.exception.ValidationException("Action type cannot be empty");
        });
    }
}
