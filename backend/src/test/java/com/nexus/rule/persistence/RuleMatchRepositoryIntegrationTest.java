package com.nexus.rule.persistence;

import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceType;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.DomainEventRepository;
import com.nexus.event.domain.EventType;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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

/**
 * Exercises RuleMatchEntity/RuleMatchRepository against a real, freshly
 * provisioned TimescaleDB instance (not H2) - Tier 3 Phase 9A. Mirrors the
 * established RuleRegistryPersistenceIntegrationTest pattern exactly:
 * Testcontainers with the same timescale/timescaledb image, Flyway pointed
 * at the container, genuinely non-trivial JSONB content (not empty maps).
 *
 * This test only proves the persistence foundation works in isolation.
 * Nothing here wires RuleMatchRepository into RuleMatcher - that remains
 * Phase 9B/9C.
 */
@SpringBootTest
@Testcontainers
class RuleMatchRepositoryIntegrationTest {

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
    private RuleMatchRepository ruleMatchRepository;
    @Autowired
    private DomainEventRepository domainEventRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SiteRepository siteRepository;
    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private DeviceRepository deviceRepository;

    @BeforeEach
    void setUp() {
        ruleMatchRepository.deleteAll();
        domainEventRepository.deleteAll();
        deviceRepository.deleteAll();
        spaceRepository.deleteAll();
        buildingRepository.deleteAll();
        siteRepository.deleteAll();
    }

    /** A real, persisted device (Phase 3) - domain_events.device_id has a real FK to devices. */
    private UUID persistedDevice(String serialSuffix) {
        Instant now = Instant.now();
        Site site = siteRepository.save(new Site(UUID.randomUUID(), "Rule Match Site", "d", now, now));
        Building building = buildingRepository.save(new Building(UUID.randomUUID(), site, "B1", "d", now, now));
        Space space = spaceRepository.save(new Space(UUID.randomUUID(), building, "S1", "d", now, now));
        Device device = deviceRepository.save(new Device(UUID.randomUUID(), space, "Device " + serialSuffix,
                DeviceType.TEMPERATURE_SENSOR, "Acme", "T1", "SN-RULEMATCH-" + serialSuffix, "d", com.nexus.device.domain.DeviceMode.SIMULATED));
        return device.getId();
    }

    private Map<String, Object> richRuleSnapshot(String name) {
        return Map.of(
                "ruleId", UUID.randomUUID().toString(),
                "name", name,
                "enabled", true,
                "priority", 3,
                "conditions", List.of(
                        Map.of("field", "EVENT_TYPE", "operator", "EQ", "expectedValue", "TWIN_UPDATED"),
                        Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0")
                ),
                "actions", List.of(
                        Map.of("actionType", "CREATE_ALERT", "parameters", Map.of("channel", "ops", "retries", 3, "urgent", true))
                ),
                "metadata", Map.of("owner", "facilities-team", "environment", "production")
        );
    }

    private Map<String, Object> richEvaluationEvidence() {
        return Map.of(
                "evaluatedConditions", List.of(
                        Map.of("field", "EVENT_TYPE", "operator", "EQ", "expectedValue", "TWIN_UPDATED",
                                "actualValue", "TWIN_UPDATED", "conditionMet", true),
                        Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0",
                                "actualValue", "34.7", "conditionMet", true)
                ),
                "outcome", "matched"
        );
    }

    private DomainEvent persistedDomainEvent() {
        // device_id is nullable on domain_events (ON DELETE SET NULL, V6) and
        // is left null here deliberately: this test only needs a real,
        // durable domain_events row to reference by event_id, not a real
        // device row (out of scope for this phase, and devices is not
        // otherwise touched by this test).
        return persistedDomainEvent(null);
    }

    private DomainEvent persistedDomainEvent(UUID deviceId) {
        // device_id has no FK constraint to devices (Phase 3's findByDeviceId
        // test below only needs a real, durable domain_events row carrying a
        // real device_id value to join against - not a real devices row).
        DomainEvent event = new DomainEvent(
                UUID.randomUUID(), EventType.TWIN_UPDATED, deviceId, Instant.now(),
                "test", Map.of("value", 34.7), "INFO", "corr-" + UUID.randomUUID());
        return domainEventRepository.save(event);
    }

    private RuleMatchEntity richMatch(UUID ruleId, UUID eventId, String correlationId) {
        return new RuleMatchEntity(
                UUID.randomUUID(), ruleId, richRuleSnapshot("High Temperature Alert"), eventId,
                correlationId, richEvaluationEvidence(), Instant.now(), "All conditions met");
    }

    // ---------- Flyway/schema (1-3) ----------

    @Test
    void flywayMigration_ruleMatchesTableExistsWithExpectedColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_name = 'rule_matches'", String.class);

        assertTrue(columns.contains("match_id"));
        assertTrue(columns.contains("rule_id"));
        assertTrue(columns.contains("rule_snapshot"));
        assertTrue(columns.contains("event_id"));
        assertTrue(columns.contains("correlation_id"));
        assertTrue(columns.contains("evaluation_evidence"));
        assertTrue(columns.contains("matched_at"));
        assertTrue(columns.contains("message"));
    }

    @Test
    void ruleMatchesTable_isNotATimescaleHypertable() {
        Integer hypertableCount = jdbcTemplate.queryForObject(
                "select count(*) from timescaledb_information.hypertables where hypertable_name = 'rule_matches'",
                Integer.class);

        assertEquals(0, hypertableCount, "rule_matches must remain a normal PostgreSQL table");
    }

    // ---------- Insert / read-back (4, 5) ----------

    @Test
    void insertAndReadBack_succeeds() {
        DomainEvent event = persistedDomainEvent();
        RuleMatchEntity match = richMatch(UUID.randomUUID(), event.eventId(), "corr-read-back");

        ruleMatchRepository.save(match);

        RuleMatchEntity fromDb = ruleMatchRepository.findById(match.getMatchId()).orElseThrow();
        assertEquals(match.getMatchId(), fromDb.getMatchId());
        assertEquals(match.getRuleId(), fromDb.getRuleId());
        assertEquals(event.eventId(), fromDb.getEventId());
        assertEquals("corr-read-back", fromDb.getCorrelationId());
    }

    // ---------- JSONB round-trip (6, 7) ----------

    @Test
    void ruleSnapshot_jsonbRoundTripsCorrectly_withGenuinelyNonTrivialContent() {
        DomainEvent event = persistedDomainEvent();
        Map<String, Object> snapshot = richRuleSnapshot("Round Trip Rule");
        RuleMatchEntity match = new RuleMatchEntity(
                UUID.randomUUID(), UUID.randomUUID(), snapshot, event.eventId(),
                "corr-snapshot-rt", richEvaluationEvidence(), Instant.now(), "All conditions met");

        ruleMatchRepository.save(match);
        ruleMatchRepository.flush();

        RuleMatchEntity fromDb = ruleMatchRepository.findById(match.getMatchId()).orElseThrow();
        assertEquals("Round Trip Rule", fromDb.getRuleSnapshot().get("name"));
        assertEquals(2, ((List<?>) fromDb.getRuleSnapshot().get("conditions")).size());
        assertEquals(1, ((List<?>) fromDb.getRuleSnapshot().get("actions")).size());
        assertEquals("facilities-team", ((Map<?, ?>) fromDb.getRuleSnapshot().get("metadata")).get("owner"));
    }

    @Test
    void evaluationEvidence_jsonbRoundTripsCorrectly_withGenuinelyNonTrivialContent() {
        DomainEvent event = persistedDomainEvent();
        RuleMatchEntity match = richMatch(UUID.randomUUID(), event.eventId(), "corr-evidence-rt");

        ruleMatchRepository.save(match);
        ruleMatchRepository.flush();

        RuleMatchEntity fromDb = ruleMatchRepository.findById(match.getMatchId()).orElseThrow();
        List<?> evaluatedConditions = (List<?>) fromDb.getEvaluationEvidence().get("evaluatedConditions");
        assertEquals(2, evaluatedConditions.size());
        assertEquals("34.7", ((Map<?, ?>) evaluatedConditions.get(1)).get("actualValue"));
        assertEquals("matched", fromDb.getEvaluationEvidence().get("outcome"));
    }

    // ---------- Timestamps/UUIDs/message survive round-trip (8, 9) ----------

    @Test
    void timestampsAndUuids_surviveRoundTrip() {
        DomainEvent event = persistedDomainEvent();
        Instant matchedAt = Instant.parse("2026-09-12T10:15:30.123Z");
        UUID ruleId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        RuleMatchEntity match = new RuleMatchEntity(
                matchId, ruleId, richRuleSnapshot("Timestamp Rule"), event.eventId(),
                "corr-ts", richEvaluationEvidence(), matchedAt, "All conditions met");

        ruleMatchRepository.save(match);

        RuleMatchEntity fromDb = ruleMatchRepository.findById(matchId).orElseThrow();
        assertEquals(matchId, fromDb.getMatchId());
        assertEquals(ruleId, fromDb.getRuleId());
        assertEquals(event.eventId(), fromDb.getEventId());
        assertEquals(matchedAt, fromDb.getMatchedAt());
    }

    @Test
    void message_survivesRoundTrip() {
        DomainEvent event = persistedDomainEvent();
        RuleMatchEntity match = new RuleMatchEntity(
                UUID.randomUUID(), UUID.randomUUID(), richRuleSnapshot("Message Rule"), event.eventId(),
                "corr-msg", richEvaluationEvidence(), Instant.now(), "Condition not met: VALUE");

        ruleMatchRepository.save(match);

        RuleMatchEntity fromDb = ruleMatchRepository.findById(match.getMatchId()).orElseThrow();
        assertEquals("Condition not met: VALUE", fromDb.getMessage());
    }

    // ---------- DomainEvent relationship (10) ----------

    @Test
    void ruleMatch_canReferenceARealPersistedDomainEvent() {
        DomainEvent event = persistedDomainEvent();
        RuleMatchEntity match = richMatch(UUID.randomUUID(), event.eventId(), "corr-domain-event");

        ruleMatchRepository.save(match);

        RuleMatchEntity fromDb = ruleMatchRepository.findById(match.getMatchId()).orElseThrow();
        assertEquals(event.eventId(), fromDb.getEventId());
        assertTrue(domainEventRepository.findById(event.eventId()).isPresent());
    }

    // ---------- Fan-out (13, 14, 15) ----------
    // Note: ActionHistory FK behavior (originally 11, 12) is deliberately not
    // tested here - the action_history.match_id -> rule_matches.match_id FK
    // is deferred to Phase 9C (Tier 3 Phase 9A revised scope) and does not
    // exist yet in this migration.

    @Test
    void multipleRuleMatchRows_canReferenceTheSameRuleId() {
        DomainEvent eventA = persistedDomainEvent();
        DomainEvent eventB = persistedDomainEvent();
        UUID sharedRuleId = UUID.randomUUID();

        ruleMatchRepository.save(richMatch(sharedRuleId, eventA.eventId(), "corr-a"));
        ruleMatchRepository.save(richMatch(sharedRuleId, eventB.eventId(), "corr-b"));

        long count = ruleMatchRepository.findAll().stream()
                .filter(m -> m.getRuleId().equals(sharedRuleId))
                .count();
        assertEquals(2, count, "the same rule must be able to match more than one event");
    }

    @Test
    void multipleRuleMatchRows_canReferenceTheSameEventId() {
        DomainEvent sharedEvent = persistedDomainEvent();
        UUID ruleA = UUID.randomUUID();
        UUID ruleB = UUID.randomUUID();

        ruleMatchRepository.save(richMatch(ruleA, sharedEvent.eventId(), "corr-fan-a"));
        ruleMatchRepository.save(richMatch(ruleB, sharedEvent.eventId(), "corr-fan-b"));

        long count = ruleMatchRepository.findAll().stream()
                .filter(m -> m.getEventId().equals(sharedEvent.eventId()))
                .count();
        assertEquals(2, count, "one event must be able to be matched by more than one rule (fan-out)");
    }

    @Test
    void noUnexpectedConstraints_preventLegitimateFanOut() {
        // A combination of the previous two: two different rules both
        // matching the same event, with each match also sharing no accidental
        // uniqueness collision on rule_id or event_id individually.
        DomainEvent sharedEvent = persistedDomainEvent();
        UUID ruleA = UUID.randomUUID();
        UUID ruleB = UUID.randomUUID();
        UUID ruleC = UUID.randomUUID();

        ruleMatchRepository.save(richMatch(ruleA, sharedEvent.eventId(), "corr-x"));
        ruleMatchRepository.save(richMatch(ruleB, sharedEvent.eventId(), "corr-y"));
        DomainEvent anotherEvent = persistedDomainEvent();
        ruleMatchRepository.save(richMatch(ruleA, anotherEvent.eventId(), "corr-z"));
        ruleMatchRepository.save(richMatch(ruleC, anotherEvent.eventId(), "corr-w"));

        assertEquals(4, ruleMatchRepository.count());
        assertFalse(ruleMatchRepository.findAll().isEmpty());
    }

    // ---------- Device-scoped rule-match history (Phase 3) ----------
    // Real evidence: RuleMatchEntity has no deviceId column of its own -
    // findByDeviceId joins through the real, persisted eventId -> DomainEvent
    // relationship, never an invented/duplicated field.

    @Test
    void findByDeviceId_returnsOnlyMatchesTriggeredByThatDevicesEvents() {
        UUID deviceA = persistedDevice("A");
        UUID deviceB = persistedDevice("B");
        DomainEvent eventFromA = persistedDomainEvent(deviceA);
        DomainEvent eventFromB = persistedDomainEvent(deviceB);
        ruleMatchRepository.save(richMatch(UUID.randomUUID(), eventFromA.eventId(), "corr-device-a"));
        ruleMatchRepository.save(richMatch(UUID.randomUUID(), eventFromB.eventId(), "corr-device-b"));

        var page = ruleMatchRepository.findByDeviceId(deviceA, PageRequest.of(0, 100));

        assertEquals(1, page.getContent().size());
        assertEquals(eventFromA.eventId(), page.getContent().get(0).getEventId());
    }

    @Test
    void findByDeviceId_deviceWithNoMatches_returnsEmptyNotError() {
        UUID deviceWithNoMatches = persistedDevice("C");
        UUID otherDevice = persistedDevice("D");
        DomainEvent eventFromOther = persistedDomainEvent(otherDevice);
        ruleMatchRepository.save(richMatch(UUID.randomUUID(), eventFromOther.eventId(), "corr-other"));

        var page = ruleMatchRepository.findByDeviceId(deviceWithNoMatches, PageRequest.of(0, 100));

        assertTrue(page.getContent().isEmpty());
    }

    @Test
    void findByDeviceId_matchFromEventWithNullDeviceId_neverMatchesARealDevice() {
        DomainEvent systemEvent = persistedDomainEvent();
        ruleMatchRepository.save(richMatch(UUID.randomUUID(), systemEvent.eventId(), "corr-system"));
        UUID realDevice = persistedDevice("E");

        var page = ruleMatchRepository.findByDeviceId(realDevice, PageRequest.of(0, 100));

        assertTrue(page.getContent().isEmpty());
    }
}
