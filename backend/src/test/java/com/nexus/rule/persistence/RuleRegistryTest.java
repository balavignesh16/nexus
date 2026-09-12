package com.nexus.rule.persistence;

import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleOperator;
import com.nexus.shared.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Behavioral tests for RuleRegistry: validation on register(), enable/disable
 * toggling, the priority-ordered/enabled-only view RuleMatcher depends on,
 * and (Tier 3 Phase 7) the persist-before-cache mutation ordering against a
 * mocked RuleRepository. Real database/hydration behavior against a genuine
 * TimescaleDB instance is covered separately in
 * RuleRegistryPersistenceIntegrationTest.
 */
class RuleRegistryTest {

    private RuleRepository repository;
    private RuleRegistry registry;

    @BeforeEach
    void setUp() {
        repository = mock(RuleRepository.class);
        registry = new RuleRegistry(repository);
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

    // ---------- Tier 3 Phase 7: persist-before-cache mutation ordering ----------

    @Test
    void register_create_persistsThenCaches() {
        Rule rule = validRule("Create Me", true, 1);

        registry.register(rule);

        ArgumentCaptor<RuleEntity> captor = ArgumentCaptor.forClass(RuleEntity.class);
        verify(repository).save(captor.capture());
        assertEquals(rule.ruleId(), captor.getValue().ruleId());
        assertEquals(rule, registry.get(rule.ruleId()).orElseThrow(),
                "cache must reflect the rule after a successful persist");
    }

    @Test
    void register_update_persistsThenCaches() {
        Rule original = validRule("Original", true, 1);
        registry.register(original);

        Rule updated = new Rule(original.ruleId(), "Updated Name", true, 9, original.conditions(), original.actions(), original.metadata());
        registry.register(updated);

        verify(repository, org.mockito.Mockito.times(2)).save(any());
        assertEquals("Updated Name", registry.get(original.ruleId()).orElseThrow().name());
        assertEquals(9, registry.get(original.ruleId()).orElseThrow().priority());
    }

    @Test
    void enable_persistsThenCaches_inThatOrder() {
        Rule rule = validRule("Disabled Initially", false, 1);
        registry.register(rule);

        registry.enable(rule.ruleId());

        ArgumentCaptor<RuleEntity> captor = ArgumentCaptor.forClass(RuleEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertTrue(captor.getValue().isEnabled(), "the second save() (from enable()) must persist enabled=true");
        assertTrue(registry.get(rule.ruleId()).orElseThrow().enabled());
    }

    @Test
    void disable_persistsThenCaches_inThatOrder() {
        Rule rule = validRule("Enabled Initially", true, 1);
        registry.register(rule);

        registry.disable(rule.ruleId());

        ArgumentCaptor<RuleEntity> captor = ArgumentCaptor.forClass(RuleEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertFalse(captor.getValue().isEnabled(), "the second save() (from disable()) must persist enabled=false");
        assertFalse(registry.get(rule.ruleId()).orElseThrow().enabled());
    }

    @Test
    void unregister_delete_persistsFirstThenRemovesFromCache() {
        Rule rule = validRule("To Delete", true, 1);
        registry.register(rule);

        registry.unregister(rule.ruleId());

        InOrder order = inOrder(repository);
        order.verify(repository).deleteById(rule.ruleId());
        assertTrue(registry.get(rule.ruleId()).isEmpty(), "cache must no longer contain the rule after a successful delete");
    }

    @Test
    void register_repositorySaveFailure_leavesCacheUnchanged() {
        Rule rule = validRule("Will Fail To Save", true, 1);
        doThrow(new RuntimeException("simulated database failure")).when(repository).save(any());

        assertThrows(RuntimeException.class, () -> registry.register(rule));

        assertTrue(registry.get(rule.ruleId()).isEmpty(),
                "a failed persist must never reach the in-memory cache");
    }

    @Test
    void unregister_repositoryDeleteFailure_leavesCacheUnchanged() {
        Rule rule = validRule("Will Fail To Delete", true, 1);
        registry.register(rule);
        doThrow(new RuntimeException("simulated database failure")).when(repository).deleteById(rule.ruleId());

        assertThrows(RuntimeException.class, () -> registry.unregister(rule.ruleId()));

        assertTrue(registry.get(rule.ruleId()).isPresent(),
                "a failed delete must leave the rule in the cache exactly as it was");
    }
}
