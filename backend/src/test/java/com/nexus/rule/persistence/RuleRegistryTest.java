package com.nexus.rule.persistence;

import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleOperator;
import com.nexus.shared.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioral tests for RuleRegistry: validation on register(), enable/disable
 * toggling, and the priority-ordered/enabled-only view RuleMatcher depends
 * on. RuleRegistry remains purely in-memory (ConcurrentHashMap) by design -
 * these tests do not add or expect persistence.
 */
class RuleRegistryTest {

    private RuleRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RuleRegistry();
    }

    private Rule validRule(String name, boolean enabled, int priority) {
        return new Rule(
                UUID.randomUUID(), name, enabled, priority,
                List.of(new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED")),
                List.of(new RuleAction("CREATE_ALERT", Map.of())),
                Map.of()
        );
    }

    @Test
    void register_validRule_canBeRetrievedById() {
        Rule rule = validRule("Test Rule", true, 1);

        registry.register(rule);

        assertTrue(registry.get(rule.ruleId()).isPresent());
        assertEquals(rule, registry.get(rule.ruleId()).get());
    }

    @Test
    void register_nullRuleId_throwsValidationException() {
        Rule rule = new Rule(null, "No ID", true, 1,
                List.of(new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED")),
                List.of(new RuleAction("CREATE_ALERT", Map.of())), Map.of());

        assertThrows(ValidationException.class, () -> registry.register(rule));
    }

    @Test
    void register_blankName_throwsValidationException() {
        Rule rule = new Rule(UUID.randomUUID(), "   ", true, 1,
                List.of(new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED")),
                List.of(new RuleAction("CREATE_ALERT", Map.of())), Map.of());

        assertThrows(ValidationException.class, () -> registry.register(rule));
    }

    @Test
    void register_noConditions_throwsValidationException() {
        Rule rule = new Rule(UUID.randomUUID(), "No Conditions", true, 1,
                List.of(), List.of(new RuleAction("CREATE_ALERT", Map.of())), Map.of());

        assertThrows(ValidationException.class, () -> registry.register(rule));
    }

    @Test
    void register_noActions_throwsValidationException() {
        Rule rule = new Rule(UUID.randomUUID(), "No Actions", true, 1,
                List.of(new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED")),
                List.of(), Map.of());

        assertThrows(ValidationException.class, () -> registry.register(rule));
    }

    @Test
    void register_blankActionType_throwsValidationException() {
        Rule rule = new Rule(UUID.randomUUID(), "Blank Action Type", true, 1,
                List.of(new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED")),
                List.of(new RuleAction("  ", Map.of())), Map.of());

        assertThrows(ValidationException.class, () -> registry.register(rule));
    }

    @Test
    void register_invalidRule_isNotAddedToRegistry() {
        Rule invalid = new Rule(UUID.randomUUID(), "", true, 1, List.of(), List.of(), Map.of());

        assertThrows(ValidationException.class, () -> registry.register(invalid));
        assertTrue(registry.list().isEmpty(), "a rule that failed validation must not be stored");
    }

    @Test
    void unregister_removesRule() {
        Rule rule = validRule("To Remove", true, 1);
        registry.register(rule);

        registry.unregister(rule.ruleId());

        assertTrue(registry.get(rule.ruleId()).isEmpty());
    }

    @Test
    void disable_thenEnable_togglesEnabledFlagWithoutLosingOtherFields() {
        Rule rule = validRule("Toggle Me", true, 5);
        registry.register(rule);

        registry.disable(rule.ruleId());
        assertFalse(registry.get(rule.ruleId()).orElseThrow().enabled());
        assertEquals(5, registry.get(rule.ruleId()).orElseThrow().priority(), "disable must not affect other fields");

        registry.enable(rule.ruleId());
        assertTrue(registry.get(rule.ruleId()).orElseThrow().enabled());
    }

    @Test
    void listEnabledOrderedByPriority_excludesDisabledRules() {
        Rule enabled = validRule("Enabled", true, 1);
        Rule disabled = validRule("Disabled", false, 2);
        registry.register(enabled);
        registry.register(disabled);

        List<Rule> result = registry.listEnabledOrderedByPriority();

        assertEquals(1, result.size());
        assertEquals(enabled.ruleId(), result.get(0).ruleId());
    }

    @Test
    void listEnabledOrderedByPriority_ordersByAscendingPriority() {
        Rule low = validRule("Priority 10", true, 10);
        Rule high = validRule("Priority 1", true, 1);
        Rule mid = validRule("Priority 5", true, 5);
        registry.register(low);
        registry.register(high);
        registry.register(mid);

        List<Rule> result = registry.listEnabledOrderedByPriority();

        assertEquals(List.of(high.ruleId(), mid.ruleId(), low.ruleId()),
                result.stream().map(Rule::ruleId).toList());
    }

    @Test
    void clear_removesAllRegisteredRules() {
        registry.register(validRule("A", true, 1));
        registry.register(validRule("B", true, 2));

        registry.clear();

        assertTrue(registry.list().isEmpty());
        assertTrue(registry.listEnabledOrderedByPriority().isEmpty());
    }

    @Test
    void list_returnsAllRulesRegardlessOfEnabledState() {
        registry.register(validRule("Enabled", true, 1));
        registry.register(validRule("Disabled", false, 2));

        assertEquals(2, registry.list().size());
    }
}
