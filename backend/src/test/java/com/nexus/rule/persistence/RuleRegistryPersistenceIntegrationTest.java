package com.nexus.rule.persistence;

import com.nexus.event.application.EventDispatcher;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.realtime.RealtimePublisher;
import com.nexus.rule.application.RuleEvaluator;
import com.nexus.rule.application.RuleMatcher;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleMatchedEvent;
import com.nexus.rule.domain.RuleOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Exercises RuleRegistry against a real, freshly-provisioned TimescaleDB
 * instance (not H2) to verify write-through persistence and startup
 * hydration for rules - directly mirroring the already-proven
 * InMemoryDigitalTwinRegistryTest pattern for Digital Twin (Tier 3 Phase 5),
 * now applied to Rule Engine persistence (Tier 3 Phase 7).
 *
 * Each test constructs its own RuleRegistry instance (rather than using the
 * Spring-managed singleton bean) so tests are isolated from each other's
 * in-memory cache state; the real RuleRepository bean (backed by the
 * Testcontainers database) is shared and cleaned per test.
 *
 * The JSONB round-trip tests deliberately use genuinely non-trivial,
 * multi-condition, non-empty-parameter content - not empty maps/lists -
 * because the existing Digital Twin JSONB coverage has only ever exercised
 * an always-empty metadata map and therefore does not prove this mechanism
 * works for real nested content (Tier 3 Phase 7 planning, section 1).
 */
@SpringBootTest
@Testcontainers
class RuleRegistryPersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    private RuleRepository ruleRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        ruleRepository.deleteAll();
    }

    private Rule richRule(String name, boolean enabled, int priority) {
        // Genuinely non-trivial content: multiple conditions with different
        // operators, a non-empty action parameter map, non-empty metadata.
        return new Rule(
                UUID.randomUUID(),
                name,
                enabled,
                priority,
                List.of(
                        new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"),
                        new RuleCondition(RuleField.SEVERITY, RuleOperator.NE, "CRITICAL"),
                        new RuleCondition(RuleField.VALUE, RuleOperator.GT, "42.5")
                ),
                List.of(
                        new RuleAction("CREATE_ALERT", Map.of("channel", "ops", "retries", 3, "urgent", true)),
                        new RuleAction("WEBHOOK", Map.of("url", "https://example.invalid/hook"))
                ),
                Map.of("owner", "facilities-team", "environment", "production")
        );
    }

    // ---------- Flyway/schema (8, 9) ----------

    @Test
    void flywayMigration_rulesTableExistsWithExpectedColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_name = 'rules'", String.class);

        assertTrue(columns.contains("rule_id"));
        assertTrue(columns.contains("name"));
        assertTrue(columns.contains("enabled"));
        assertTrue(columns.contains("priority"));
        assertTrue(columns.contains("conditions"));
        assertTrue(columns.contains("actions"));
        assertTrue(columns.contains("metadata"));
    }

    // ---------- CRUD persistence (10-14) ----------

    @Test
    void register_create_persistsToRealDatabase() {
        RuleRegistry registry = new RuleRegistry(ruleRepository);
        Rule rule = richRule("Create Test", true, 1);

        registry.register(rule);

        RuleEntity fromDb = ruleRepository.findById(rule.ruleId()).orElseThrow();
        assertEquals("Create Test", fromDb.getName());
    }

    @Test
    void register_update_persistsToRealDatabase() {
        RuleRegistry registry = new RuleRegistry(ruleRepository);
        Rule original = richRule("Before Update", true, 1);
        registry.register(original);

        Rule updated = new Rule(original.ruleId(), "After Update", true, 7, original.conditions(), original.actions(), original.metadata());
        registry.register(updated);

        RuleEntity fromDb = ruleRepository.findById(original.ruleId()).orElseThrow();
        assertEquals("After Update", fromDb.getName());
        assertEquals(7, fromDb.getPriority());
        assertEquals(1, ruleRepository.count(), "an update must not create a second row for the same rule_id");
    }

    @Test
    void enable_persistsStateToRealDatabase() {
        RuleRegistry registry = new RuleRegistry(ruleRepository);
        Rule rule = richRule("Enable Test", false, 1);
        registry.register(rule);

        registry.enable(rule.ruleId());

        assertTrue(ruleRepository.findById(rule.ruleId()).orElseThrow().isEnabled());
    }

    @Test
    void disable_persistsStateToRealDatabase() {
        RuleRegistry registry = new RuleRegistry(ruleRepository);
        Rule rule = richRule("Disable Test", true, 1);
        registry.register(rule);

        registry.disable(rule.ruleId());

        assertFalse(ruleRepository.findById(rule.ruleId()).orElseThrow().isEnabled());
    }

    @Test
    void unregister_deletesFromRealDatabase() {
        RuleRegistry registry = new RuleRegistry(ruleRepository);
        Rule rule = richRule("Delete Test", true, 1);
        registry.register(rule);

        registry.unregister(rule.ruleId());

        assertTrue(ruleRepository.findById(rule.ruleId()).isEmpty());
    }

    // ---------- JSONB round-trip with genuinely non-trivial content (15-18) ----------

    @Test
    void priorityRoundTripsCorrectly() {
        RuleRegistry writer = new RuleRegistry(ruleRepository);
        Rule rule = richRule("Priority RT", true, 37);
        writer.register(rule);

        RuleRegistry fresh = new RuleRegistry(ruleRepository);
        fresh.init();

        assertEquals(37, fresh.get(rule.ruleId()).orElseThrow().priority());
    }

    @Test
    void conditionsRoundTrip_withMultipleDifferentOperators() {
        RuleRegistry writer = new RuleRegistry(ruleRepository);
        Rule rule = richRule("Conditions RT", true, 1);
        writer.register(rule);

        RuleRegistry fresh = new RuleRegistry(ruleRepository);
        fresh.init();

        assertEquals(rule.conditions(), fresh.get(rule.ruleId()).orElseThrow().conditions());
    }

    @Test
    void actionsRoundTrip_withNonEmptyParameterMaps() {
        RuleRegistry writer = new RuleRegistry(ruleRepository);
        Rule rule = richRule("Actions RT", true, 1);
        writer.register(rule);

        RuleRegistry fresh = new RuleRegistry(ruleRepository);
        fresh.init();

        assertEquals(rule.actions(), fresh.get(rule.ruleId()).orElseThrow().actions());
    }

    @Test
    void metadataRoundTrips_withNonEmptyContent() {
        RuleRegistry writer = new RuleRegistry(ruleRepository);
        Rule rule = richRule("Metadata RT", true, 1);
        writer.register(rule);

        RuleRegistry fresh = new RuleRegistry(ruleRepository);
        fresh.init();

        assertEquals(rule.metadata(), fresh.get(rule.ruleId()).orElseThrow().metadata());
    }

    // ---------- Hydration (19-23) ----------

    @Test
    void hydration_multipleRulesLoadCorrectly() {
        RuleRegistry writer = new RuleRegistry(ruleRepository);
        Rule a = richRule("Rule A", true, 1);
        Rule b = richRule("Rule B", true, 2);
        writer.register(a);
        writer.register(b);

        RuleRegistry fresh = new RuleRegistry(ruleRepository);
        fresh.init();

        assertEquals(2, fresh.list().size());
    }

    @Test
    void hydration_emptyDatabase_resultsInEmptyRegistry() {
        RuleRegistry registry = new RuleRegistry(ruleRepository);

        registry.init();

        assertTrue(registry.list().isEmpty());
    }

    @Test
    void hydration_preservesEnabledAndDisabledState() {
        RuleRegistry writer = new RuleRegistry(ruleRepository);
        Rule enabledRule = richRule("Stays Enabled", true, 1);
        Rule disabledRule = richRule("Stays Disabled", false, 2);
        writer.register(enabledRule);
        writer.register(disabledRule);

        RuleRegistry fresh = new RuleRegistry(ruleRepository);
        fresh.init();

        assertTrue(fresh.get(enabledRule.ruleId()).orElseThrow().enabled());
        assertFalse(fresh.get(disabledRule.ruleId()).orElseThrow().enabled());
    }

    @Test
    void hydration_priorityOrderingIsPreserved() {
        RuleRegistry writer = new RuleRegistry(ruleRepository);
        Rule low = richRule("Low Priority Number", true, 1);
        Rule high = richRule("High Priority Number", true, 10);
        writer.register(high);
        writer.register(low);

        RuleRegistry fresh = new RuleRegistry(ruleRepository);
        fresh.init();

        List<UUID> order = fresh.listEnabledOrderedByPriority().stream().map(Rule::ruleId).toList();
        assertEquals(List.of(low.ruleId(), high.ruleId()), order);
    }

    @Test
    void freshRegistry_loadsRulesPersistedByAPreviousRegistryInstance_sameDatabase() {
        // Stands in for "restart the backend": a completely separate
        // RuleRegistry instance, sharing only the real database, must see
        // exactly what a prior instance persisted - this is the strongest
        // deterministic proxy for a true process restart, avoiding the
        // brittleness of actually killing/relaunching a JVM inside a test.
        RuleRegistry instanceBeforeRestart = new RuleRegistry(ruleRepository);
        Rule rule = richRule("Survives Restart", true, 5);
        instanceBeforeRestart.register(rule);

        RuleRegistry instanceAfterRestart = new RuleRegistry(ruleRepository);
        assertTrue(instanceAfterRestart.get(rule.ruleId()).isEmpty(),
                "a brand new registry must start with an empty cache, before init() runs");

        instanceAfterRestart.init();

        Rule hydrated = instanceAfterRestart.get(rule.ruleId()).orElseThrow();
        assertEquals(rule, hydrated, "every field must survive exactly, matching the original");
    }

    // ---------- Hydrated rules are usable by RuleMatcher (24) ----------

    @Test
    void hydratedRule_isEvaluatedAndMatchedByRuleMatcher() {
        RuleRegistry writer = new RuleRegistry(ruleRepository);
        Rule rule = new Rule(
                UUID.randomUUID(), "Matches Twin Updates", true, 1,
                List.of(new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED")),
                List.of(new RuleAction("CREATE_ALERT", Map.of())),
                Map.of()
        );
        writer.register(rule);

        RuleRegistry hydratedRegistry = new RuleRegistry(ruleRepository);
        hydratedRegistry.init();

        EventDispatcher eventDispatcher = mock(EventDispatcher.class);
        RuleEvaluator realEvaluator = new RuleEvaluator();
        BoundedRuleMatchStore matchStore = new BoundedRuleMatchStore();
        ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);
        RealtimePublisher realtimePublisher = mock(RealtimePublisher.class);
        RuleMatcher ruleMatcher = new RuleMatcher(eventDispatcher, hydratedRegistry, realEvaluator, matchStore, applicationEventPublisher, realtimePublisher);

        DomainEvent event = new DomainEvent(UUID.randomUUID(), EventType.TWIN_UPDATED, UUID.randomUUID(),
                Instant.now(), "test", Map.of(), "INFO", "corr-hydrated");

        ruleMatcher.onEvent(event);

        assertEquals(1, matchStore.getRecentMatches(10).size(), "the hydrated rule must actually match, exactly as an in-session-created rule would");
        verify(realtimePublisher).publishRuleMatch(any(), org.mockito.ArgumentMatchers.eq("corr-hydrated"));
        verify(applicationEventPublisher).publishEvent(any(RuleMatchedEvent.class));
    }
}
