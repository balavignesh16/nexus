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
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private RuleMatchRepository ruleMatchRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private com.nexus.device.capability.DeviceCapabilityRepository deviceCapabilityRepository;
    @Autowired
    private com.nexus.device.domain.DeviceRepository deviceRepository;
    @Autowired
    private com.nexus.twin.domain.DigitalTwinRegistry digitalTwinRegistry;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE rule_matches, rule_validation_attempts, rule_version_workflow, rule_versions, rules CASCADE");
    }

    @Autowired private com.nexus.rule.application.RuleLifecycleService lifecycle;
    @Autowired private RuleVersionStore versions;
    private RuleRegistry registry() {
        RuleRegistry registry = new RuleRegistry(ruleRepository, deviceCapabilityRepository, deviceRepository);
        registry.setLifecycle(lifecycle, versions);
        return registry;
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
        RuleRegistry registry = registry();
        Rule rule = richRule("Create Test", true, 1);

        registry.register(rule);

        RuleEntity fromDb = ruleRepository.findById(rule.ruleId()).orElseThrow();
        assertEquals("Create Test", fromDb.getName());
    }

    @Test
    void register_update_persistsToRealDatabase() {
        RuleRegistry registry = registry();
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
        RuleRegistry registry = registry();
        Rule rule = richRule("Enable Test", false, 1);
        registry.register(rule);

        registry.enable(rule.ruleId());

        assertTrue(ruleRepository.findById(rule.ruleId()).orElseThrow().isEnabled());
    }

    @Test
    void disable_persistsStateToRealDatabase() {
        RuleRegistry registry = registry();
        Rule rule = richRule("Disable Test", true, 1);
        registry.register(rule);

        registry.disable(rule.ruleId());

        assertFalse(ruleRepository.findById(rule.ruleId()).orElseThrow().isEnabled());
    }

    @Test
    void unregister_deletesFromRealDatabase() {
        RuleRegistry registry = registry();
        Rule rule = richRule("Delete Test", true, 1);
        registry.register(rule);

        registry.unregister(rule.ruleId());

        assertTrue(registry.get(rule.ruleId()).isEmpty());
        assertTrue(ruleRepository.findById(rule.ruleId()).isPresent(), "Archive preserves logical ownership and immutable history");
    }

    // ---------- JSONB round-trip with genuinely non-trivial content (15-18) ----------

    @Test
    void priorityRoundTripsCorrectly() {
        RuleRegistry writer = registry();
        Rule rule = richRule("Priority RT", true, 37);
        writer.register(rule);

        RuleRegistry fresh = registry();
        fresh.init();

        assertEquals(37, fresh.get(rule.ruleId()).orElseThrow().priority());
    }

    @Test
    void conditionsRoundTrip_withMultipleDifferentOperators() {
        RuleRegistry writer = registry();
        Rule rule = richRule("Conditions RT", true, 1);
        writer.register(rule);

        RuleRegistry fresh = registry();
        fresh.init();

        assertEquals(rule.conditions(), fresh.get(rule.ruleId()).orElseThrow().conditions());
    }

    @Test
    void actionsRoundTrip_withNonEmptyParameterMaps() {
        RuleRegistry writer = registry();
        Rule rule = richRule("Actions RT", true, 1);
        writer.register(rule);

        RuleRegistry fresh = registry();
        fresh.init();

        assertEquals(rule.actions(), fresh.get(rule.ruleId()).orElseThrow().actions());
    }

    @Test
    void metadataRoundTrips_withNonEmptyContent() {
        RuleRegistry writer = registry();
        Rule rule = richRule("Metadata RT", true, 1);
        writer.register(rule);

        RuleRegistry fresh = registry();
        fresh.init();

        assertEquals(rule.metadata(), fresh.get(rule.ruleId()).orElseThrow().metadata());
    }

    // ---------- Hydration (19-23) ----------

    @Test
    void hydration_multipleRulesLoadCorrectly() {
        RuleRegistry writer = registry();
        Rule a = richRule("Rule A", true, 1);
        Rule b = richRule("Rule B", true, 2);
        writer.register(a);
        writer.register(b);

        RuleRegistry fresh = registry();
        fresh.init();

        assertEquals(2, fresh.list().size());
    }

    @Test
    void hydration_emptyDatabase_resultsInEmptyRegistry() {
        RuleRegistry registry = registry();

        registry.init();

        assertTrue(registry.list().isEmpty());
    }

    @Test
    void hydration_preservesEnabledAndDisabledState() {
        RuleRegistry writer = registry();
        Rule enabledRule = richRule("Stays Enabled", true, 1);
        Rule disabledRule = richRule("Stays Disabled", false, 2);
        writer.register(enabledRule);
        writer.register(disabledRule);

        RuleRegistry fresh = registry();
        fresh.init();

        assertTrue(fresh.get(enabledRule.ruleId()).orElseThrow().enabled());
        assertFalse(fresh.get(disabledRule.ruleId()).orElseThrow().enabled());
    }

    @Test
    void hydration_priorityOrderingIsPreserved() {
        RuleRegistry writer = registry();
        Rule low = richRule("Low Priority Number", true, 1);
        Rule high = richRule("High Priority Number", true, 10);
        writer.register(high);
        writer.register(low);

        RuleRegistry fresh = registry();
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
        RuleRegistry instanceBeforeRestart = registry();
        Rule rule = richRule("Survives Restart", true, 5);
        instanceBeforeRestart.register(rule);

        RuleRegistry instanceAfterRestart = registry();
        assertTrue(instanceAfterRestart.get(rule.ruleId()).isPresent(),
                "Database ownership is readable independently of cache hydration");

        instanceAfterRestart.init();

        Rule hydrated = instanceAfterRestart.get(rule.ruleId()).orElseThrow();
        assertEquals(rule, hydrated, "every field must survive exactly, matching the original");
    }

    // ---------- Hydrated rules are usable by RuleMatcher (24) ----------

    @Test
    void hydratedRule_isEvaluatedAndMatchedByRuleMatcher() {
        RuleRegistry writer = registry();
        Rule rule = new Rule(
                UUID.randomUUID(), "Matches Twin Updates", true, 1,
                List.of(
                        new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"),
                        new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30.0")
                ),
                List.of(new RuleAction("CREATE_ALERT", Map.of())),
                Map.of()
        );
        writer.register(rule);

        RuleRegistry hydratedRegistry = registry();
        hydratedRegistry.init();

        EventDispatcher eventDispatcher = mock(EventDispatcher.class);
        RuleEvaluator realEvaluator = new RuleEvaluator(digitalTwinRegistry);
        BoundedRuleMatchStore matchStore = new BoundedRuleMatchStore();
        ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);
        RealtimePublisher realtimePublisher = mock(RealtimePublisher.class);
        RuleMatchPersister ruleMatchPersister = new RuleMatchPersister(ruleMatchRepository, objectMapper);
        RuleMatcher ruleMatcher = new RuleMatcher(eventDispatcher, hydratedRegistry, realEvaluator, matchStore, applicationEventPublisher, realtimePublisher, ruleMatchPersister, ruleMatchRepository);

        DomainEvent event = new DomainEvent(UUID.randomUUID(), EventType.TWIN_UPDATED, UUID.randomUUID(),
                Instant.now(), "test", Map.of("value", 34.7), "INFO", "corr-hydrated");

        ruleMatcher.onEvent(event);

        assertEquals(1, matchStore.getRecentMatches(10).size(), "the hydrated rule must actually match, exactly as an in-session-created rule would");
        verify(realtimePublisher).publishRuleMatch(any(), org.mockito.ArgumentMatchers.eq("corr-hydrated"));
        verify(applicationEventPublisher).publishEvent(any(RuleMatchedEvent.class));

        // ---------- Tier 3 Phase 9B: the match must also be durably persisted ----------

        RuleMatchedEvent matched = matchStore.getRecentMatches(1).get(0);
        RuleMatchEntity persisted = ruleMatchRepository.findById(matched.matchId()).orElseThrow();

        assertEquals(matched.matchId(), persisted.getMatchId());
        assertEquals(rule.ruleId(), persisted.getRuleId());
        assertEquals(event.eventId(), persisted.getEventId());
        assertEquals("corr-hydrated", persisted.getCorrelationId());
        assertEquals("Matches Twin Updates", persisted.getRuleSnapshot().get("name"),
                "rule_snapshot must contain the evaluated rule");
        assertEquals(2, ((List<?>) persisted.getRuleSnapshot().get("conditions")).size());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evaluatedConditions =
                (List<Map<String, Object>>) persisted.getEvaluationEvidence().get("evaluatedConditions");
        assertEquals(2, evaluatedConditions.size(), "evaluation_evidence must contain both evaluated conditions");
        assertEquals(34.7, evaluatedConditions.get(1).get("actualValue"),
                "evaluation_evidence must preserve the actual observed value");
        assertEquals("matched", persisted.getEvaluationEvidence().get("outcome"));
        assertEquals("All conditions met", persisted.getMessage());
    }

    // ---------- Tier 3 Phase 11: durable rule-match retrieval API ----------

    private com.nexus.rule.api.RuleController ruleMatchHistoryController() {
        return new com.nexus.rule.api.RuleController(
                registry(), new BoundedRuleMatchStore(), ruleMatchRepository);
    }

    private RuleMatchedEvent triggerRealMatch(RuleRegistry registry, Rule rule, double value, String correlationId) {
        EventDispatcher eventDispatcher = mock(EventDispatcher.class);
        RuleEvaluator realEvaluator = new RuleEvaluator(digitalTwinRegistry);
        BoundedRuleMatchStore localMatchStore = new BoundedRuleMatchStore();
        ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);
        RealtimePublisher realtimePublisher = mock(RealtimePublisher.class);
        RuleMatchPersister persister = new RuleMatchPersister(ruleMatchRepository, objectMapper);
        RuleMatcher matcher = new RuleMatcher(eventDispatcher, registry, realEvaluator, localMatchStore,
                applicationEventPublisher, realtimePublisher, persister, ruleMatchRepository);

        DomainEvent event = new DomainEvent(UUID.randomUUID(), EventType.TWIN_UPDATED, UUID.randomUUID(),
                Instant.now(), "test", Map.of("value", value), "INFO", correlationId);
        matcher.onEvent(event);

        return localMatchStore.getRecentMatches(1).get(0);
    }

    @Test
    void historyApi_listEndpoint_returnsDurableMatch() {
        RuleRegistry registry = registry();
        Rule rule = new Rule(UUID.randomUUID(), "History List Rule", true, 1,
                List.of(new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30.0")),
                List.of(new RuleAction("CREATE_ALERT", Map.of())), Map.of());
        registry.register(rule);
        triggerRealMatch(registry, rule, 34.7, "corr-list-api");

        var response = ruleMatchHistoryController().getRuleMatchHistory(100, null, null, null);

        assertTrue(response.getBody().stream().anyMatch(m -> "History List Rule".equals(m.ruleName())));
    }

    @Test
    void historyApi_orderingIsMatchedAtDescending() {
        RuleRegistry registry = registry();
        Rule rule = new Rule(UUID.randomUUID(), "Ordering Rule", true, 1,
                List.of(new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30.0")),
                List.of(new RuleAction("CREATE_ALERT", Map.of())), Map.of());
        registry.register(rule);
        triggerRealMatch(registry, rule, 31.0, "corr-order-1");
        triggerRealMatch(registry, rule, 32.0, "corr-order-2");

        var response = ruleMatchHistoryController().getRuleMatchHistory(100, rule.ruleId(), null, null);

        List<Instant> timestamps = response.getBody().stream().map(m -> m.matchedAt()).toList();
        assertTrue(timestamps.size() >= 2);
        for (int i = 0; i < timestamps.size() - 1; i++) {
            assertFalse(timestamps.get(i).isBefore(timestamps.get(i + 1)), "results must be ordered matchedAt DESC");
        }
    }

    @Test
    void historyApi_ruleIdFilter_returnsOnlyThatRulesMatches() {
        RuleRegistry registry = registry();
        Rule ruleA = new Rule(UUID.randomUUID(), "Filter Rule A", true, 1,
                List.of(new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30.0")),
                List.of(new RuleAction("CREATE_ALERT", Map.of())), Map.of());
        Rule ruleB = new Rule(UUID.randomUUID(), "Filter Rule B", true, 1,
                List.of(new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30.0")),
                List.of(new RuleAction("CREATE_ALERT", Map.of())), Map.of());
        registry.register(ruleA);
        registry.register(ruleB);
        triggerRealMatch(registry, ruleA, 35.0, "corr-filter-a");
        triggerRealMatch(registry, ruleB, 36.0, "corr-filter-b");

        var response = ruleMatchHistoryController().getRuleMatchHistory(100, ruleA.ruleId(), null, null);

        assertTrue(response.getBody().stream().allMatch(m -> m.ruleId().equals(ruleA.ruleId())));
        assertTrue(response.getBody().stream().anyMatch(m -> "Filter Rule A".equals(m.ruleName())));
    }

    @Test
    void historyApi_correlationIdFilter_returnsOnlyThatCorrelation() {
        RuleRegistry registry = registry();
        Rule rule = new Rule(UUID.randomUUID(), "Correlation Filter Rule", true, 1,
                List.of(new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30.0")),
                List.of(new RuleAction("CREATE_ALERT", Map.of())), Map.of());
        registry.register(rule);
        triggerRealMatch(registry, rule, 33.0, "corr-unique-xyz");

        var response = ruleMatchHistoryController().getRuleMatchHistory(100, null, "corr-unique-xyz", null);

        assertEquals(1, response.getBody().size());
        assertEquals("corr-unique-xyz", response.getBody().get(0).correlationId());
    }

    @Test
    void historyApi_detailEndpoint_returnsPersistedSnapshotAndEvidenceWithNumericActualValue() {
        RuleRegistry registry = registry();
        Rule rule = new Rule(UUID.randomUUID(), "Detail Endpoint Rule", true, 1,
                List.of(new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30.0")),
                List.of(new RuleAction("CREATE_ALERT", Map.of())), Map.of());
        registry.register(rule);
        RuleMatchedEvent match = triggerRealMatch(registry, rule, 34.7, "corr-detail-api");

        var response = ruleMatchHistoryController().getRuleMatchDetail(match.matchId());

        var detail = response.getBody();
        assertEquals(match.matchId(), detail.matchId());
        assertEquals(rule.ruleId(), detail.ruleId());
        assertEquals("Detail Endpoint Rule", detail.ruleSnapshot().get("name"));
        assertEquals("corr-detail-api", detail.correlationId());
        assertEquals("All conditions met", detail.message());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evaluatedConditions =
                (List<Map<String, Object>>) detail.evaluationEvidence().get("evaluatedConditions");
        Object actualValue = evaluatedConditions.get(0).get("actualValue");
        assertTrue(actualValue instanceof Number, "actualValue must remain a JSON number, not a stringified value");
        assertEquals(34.7, ((Number) actualValue).doubleValue());
    }

    @Test
    void historyApi_unknownMatchId_throwsResourceNotFoundException() {
        assertThrows(com.nexus.shared.exception.ResourceNotFoundException.class,
                () -> ruleMatchHistoryController().getRuleMatchDetail(UUID.randomUUID()));
    }

    @Test
    void historyApi_restartSurvival_freshControllerInstanceStillReturnsMatch() {
        // Stands in for "restart the backend": a brand new RuleController
        // instance (fresh RuleRegistry, fresh empty BoundedRuleMatchStore),
        // sharing only the real database, must still be able to retrieve the
        // match through the durable history endpoint - unlike GET
        // /api/v1/rule-matches, which is expected to return [] after a
        // restart because it reads only the volatile cache.
        RuleRegistry writerRegistry = registry();
        Rule rule = new Rule(UUID.randomUUID(), "Restart Survival Rule", true, 1,
                List.of(new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30.0")),
                List.of(new RuleAction("CREATE_ALERT", Map.of())), Map.of());
        writerRegistry.register(rule);
        RuleMatchedEvent match = triggerRealMatch(writerRegistry, rule, 34.7, "corr-restart");

        // "After restart": a completely fresh controller, backed only by the
        // shared real database.
        com.nexus.rule.api.RuleController freshController = new com.nexus.rule.api.RuleController(
                registry(), new BoundedRuleMatchStore(), ruleMatchRepository);

        // The volatile cache-backed endpoint on a fresh instance is empty...
        assertTrue(freshController.getRuleMatches(100).getBody().isEmpty(),
                "a fresh BoundedRuleMatchStore must start empty, exactly as it would after a real restart");

        // ...but the durable history endpoint still returns the match.
        var historyResponse = freshController.getRuleMatchHistory(100, null, "corr-restart", null);
        assertEquals(1, historyResponse.getBody().size());

        var detailResponse = freshController.getRuleMatchDetail(match.matchId());
        assertEquals("Restart Survival Rule", detailResponse.getBody().ruleSnapshot().get("name"));
    }

    @Test
    void historyApi_ruleEditAfterMatch_historyStillReportsOriginalSnapshot() {
        RuleRegistry registry = registry();
        Rule original = new Rule(UUID.randomUUID(), "Edit Integrity Rule", true, 1,
                List.of(new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30.0")),
                List.of(new RuleAction("CREATE_ALERT", Map.of())), Map.of());
        registry.register(original);
        RuleMatchedEvent match = triggerRealMatch(registry, original, 34.7, "corr-edit-integrity");

        // Edit the live rule to a materially different condition.
        Rule edited = new Rule(original.ruleId(), original.name(), true, 1,
                List.of(new RuleCondition(RuleField.VALUE, RuleOperator.GT, "40.0")),
                original.actions(), original.metadata());
        registry.register(edited);
        assertEquals("40.0", registry.get(original.ruleId()).orElseThrow().conditions().get(0).expectedValue(),
                "sanity check: the live rule must now require > 40.0");

        var detailResponse = ruleMatchHistoryController().getRuleMatchDetail(match.matchId());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conditions =
                (List<Map<String, Object>>) detailResponse.getBody().ruleSnapshot().get("conditions");
        assertEquals("30.0", conditions.get(0).get("expectedValue"),
                "the durable history API must report the rule configuration as it was AT MATCH TIME, not the current live rule");
    }
}
