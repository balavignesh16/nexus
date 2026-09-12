package com.nexus.rule.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Plain-object field-mapping tests for {@link RuleMatchEntity} (Tier 3 Phase
 * 9A). These do not touch a database - they only verify the entity's
 * constructor/accessors correctly hold every field, including genuinely
 * non-trivial nested JSON content, mirroring the same discipline
 * RuleRegistryPersistenceIntegrationTest already established for RuleEntity
 * (non-empty maps/lists, not just empty ones). Real JSONB round-trip against
 * a database is covered separately in RuleMatchRepositoryIntegrationTest.
 */
class RuleMatchEntityTest {

    @Test
    void constructor_allScalarFieldsMapCorrectly() {
        UUID matchId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant matchedAt = Instant.parse("2026-09-12T10:15:30Z");

        RuleMatchEntity entity = new RuleMatchEntity(
                matchId, ruleId, Map.of("name", "Test Rule"), eventId,
                "corr-123", Map.of("field", "VALUE"), matchedAt, "All conditions met");

        assertEquals(matchId, entity.getMatchId());
        assertEquals(ruleId, entity.getRuleId());
        assertEquals(eventId, entity.getEventId());
        assertEquals("corr-123", entity.getCorrelationId());
        assertEquals(matchedAt, entity.getMatchedAt());
        assertEquals("All conditions met", entity.getMessage());
    }

    @Test
    void constructor_uuidFieldsAreDistinctAndPreserved() {
        UUID matchId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        RuleMatchEntity entity = new RuleMatchEntity(
                matchId, ruleId, Map.of(), eventId, null, Map.of(), Instant.now(), null);

        assertEquals(matchId, entity.getMatchId());
        assertEquals(ruleId, entity.getRuleId());
        assertEquals(eventId, entity.getEventId());
        assertNotSame(matchId, ruleId);
    }

    private static void assertNotSame(UUID a, UUID b) {
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }

    @Test
    void correlationIdAndMessage_canBeNull() {
        RuleMatchEntity entity = new RuleMatchEntity(
                UUID.randomUUID(), UUID.randomUUID(), Map.of("a", 1), UUID.randomUUID(),
                null, Map.of("b", 2), Instant.now(), null);

        assertNull(entity.getCorrelationId());
        assertNull(entity.getMessage());
    }

    @Test
    void ruleSnapshot_holdsNonEmptyNestedStructure_withMultipleConditionsAndActions() {
        Map<String, Object> snapshot = Map.of(
                "ruleId", UUID.randomUUID().toString(),
                "name", "High Temperature Alert",
                "enabled", true,
                "priority", 3,
                "conditions", List.of(
                        Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0"),
                        Map.of("field", "SEVERITY", "operator", "NE", "expectedValue", "INFO")
                ),
                "actions", List.of(
                        Map.of("actionType", "CREATE_ALERT", "parameters", Map.of("channel", "ops", "retries", 3)),
                        Map.of("actionType", "WEBHOOK", "parameters", Map.of("url", "https://example.invalid/hook"))
                ),
                "metadata", Map.of("owner", "facilities-team", "environment", "production")
        );

        RuleMatchEntity entity = new RuleMatchEntity(
                UUID.randomUUID(), UUID.randomUUID(), snapshot, UUID.randomUUID(),
                "corr-snap", Map.of("evaluated", true), Instant.now(), "All conditions met");

        assertEquals(snapshot, entity.getRuleSnapshot());
        assertEquals(2, ((List<?>) entity.getRuleSnapshot().get("conditions")).size());
        assertEquals(2, ((List<?>) entity.getRuleSnapshot().get("actions")).size());
        assertEquals("facilities-team", ((Map<?, ?>) entity.getRuleSnapshot().get("metadata")).get("owner"));
    }

    @Test
    void evaluationEvidence_holdsNonEmptyStructuredContent() {
        Map<String, Object> evidence = Map.of(
                "evaluatedConditions", List.of(
                        Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0",
                                "actualValue", "34.7", "conditionMet", true),
                        Map.of("field", "SEVERITY", "operator", "NE", "expectedValue", "INFO",
                                "actualValue", "WARNING", "conditionMet", true)
                ),
                "outcome", "matched"
        );

        RuleMatchEntity entity = new RuleMatchEntity(
                UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "Evidence Rule"), UUID.randomUUID(),
                "corr-evidence", evidence, Instant.now(), "All conditions met");

        assertEquals(evidence, entity.getEvaluationEvidence());
        assertEquals("34.7", ((Map<?, ?>) ((List<?>) entity.getEvaluationEvidence()
                .get("evaluatedConditions")).get(0)).get("actualValue"));
    }

    @Test
    void ruleSnapshot_withNonAsciiRuleName_isPreserved() {
        Map<String, Object> snapshot = Map.of(
                "name", "温度警報 – Température élevée ⚠",
                "enabled", true
        );

        RuleMatchEntity entity = new RuleMatchEntity(
                UUID.randomUUID(), UUID.randomUUID(), snapshot, UUID.randomUUID(),
                "corr-unicode", Map.of("note", "unicode test"), Instant.now(), null);

        assertEquals("温度警報 – Température élevée ⚠",
                entity.getRuleSnapshot().get("name"));
    }
}
