package com.nexus.rule.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.DomainEventRepository;
import com.nexus.event.domain.EventType;
import com.nexus.rule.ai.AiCandidateParser;
import com.nexus.rule.ai.AiExplanationContextMapper;
import com.nexus.rule.ai.AiReasoningBoundary;
import com.nexus.rule.ai.DeterministicExplanationRenderer;
import com.nexus.rule.ai.GroundingChecker;
import com.nexus.rule.ai.RuleMatchExplanation;
import com.nexus.rule.ai.RuleMatchExplanationContextBuilder;
import com.nexus.rule.ai.RuleMatchExplanationService;
import com.nexus.rule.persistence.RuleMatchEntity;
import com.nexus.rule.persistence.RuleMatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 4 Phase 2D — provider-failure containment (real TimescaleDB), reusing
 * the identical Testcontainers pattern already established by
 * RuleMatchExplanationIntegrationTest. Proves the one property no
 * fixture-only evaluator can honestly prove: that an AI/provider failure
 * never mutates the durable, already-persisted RuleMatch row it is
 * explaining.
 *
 * MQTT/Digital Twin/realtime are deliberately NOT simulated here - no code
 * path connects RuleMatchExplanationService to any of them (confirmed
 * across every prior Tier 4 audit), so there is nothing for this test to
 * exercise there; asserting their non-interference would be testing an
 * absence of wiring that already doesn't exist, not a Phase 2D concern.
 */
@SpringBootTest
@Testcontainers
class FailureContainmentEvaluationTest {

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
    private ActionHistoryRepository actionHistoryRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private RuleMatchExplanationContextBuilder contextBuilder;

    @BeforeEach
    void setUp() {
        actionHistoryRepository.deleteAll();
        ruleMatchRepository.deleteAll();
        domainEventRepository.deleteAll();
        contextBuilder = new RuleMatchExplanationContextBuilder(ruleMatchRepository, domainEventRepository, actionHistoryRepository);
    }

    private Map<String, Object> richRuleSnapshot(String name, String expectedValue) {
        return Map.of(
                "ruleId", UUID.randomUUID().toString(),
                "name", name,
                "enabled", true,
                "priority", 1,
                "conditions", List.of(Map.of("field", "VALUE", "operator", "GT", "expectedValue", expectedValue)),
                "actions", List.of(Map.of("actionType", "CREATE_ALERT", "parameters", Map.of())),
                "metadata", Map.of());
    }

    private Map<String, Object> richEvidence(double actualValue) {
        return Map.of(
                "outcome", "matched",
                "evaluatedConditions", List.of(
                        Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0",
                                "actualValue", actualValue, "conditionMet", true)));
    }

    @Test
    void providerFailure_doesNotMutatePersistedRuleMatch() {
        DomainEvent event = domainEventRepository.save(new DomainEvent(
                UUID.randomUUID(), EventType.TWIN_UPDATED, null, Instant.now(), "test",
                Map.of("value", 34.7), "INFO", "corr-containment"));

        UUID matchId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        Instant matchedAt = Instant.now();
        RuleMatchEntity original = new RuleMatchEntity(
                matchId, ruleId, richRuleSnapshot("Failure Containment Rule", "30.0"), event.eventId(),
                "corr-containment", richEvidence(34.7), matchedAt, "All conditions met");
        ruleMatchRepository.save(original);

        // 1/2: the match exists, exactly as persisted.
        RuleMatchEntity beforeReload = ruleMatchRepository.findById(matchId).orElseThrow();
        assertEquals("Failure Containment Rule", beforeReload.getRuleSnapshot().get("name"));

        // 3/4: inject a throwing AiReasoningBoundary and request an explanation.
        AiReasoningBoundary throwingBoundary = ctx -> {
            throw new RuntimeException("simulated provider failure - must never reach the persisted row");
        };
        GroundingChecker groundingChecker = new GroundingChecker(objectMapper);
        DeterministicExplanationRenderer renderer = new DeterministicExplanationRenderer();
        RuleMatchExplanationService service = new RuleMatchExplanationService(
                contextBuilder, new AiExplanationContextMapper(), throwingBoundary,
                new AiCandidateParser(objectMapper), groundingChecker, renderer, true);

        RuleMatchExplanation explanation = service.explain(matchId);

        // 5: deterministic fallback, not an exception, not a fabricated AI explanation.
        assertEquals("DETERMINISTIC_FALLBACK", explanation.generatedBy());
        assertEquals(matchId, explanation.matchId());

        // 6/7: reload the persisted row and confirm it is byte-for-byte unchanged.
        RuleMatchEntity afterReload = ruleMatchRepository.findById(matchId).orElseThrow();
        assertEquals(original.getMatchId(), afterReload.getMatchId());
        assertEquals(original.getRuleId(), afterReload.getRuleId());
        assertEquals(original.getRuleSnapshot(), afterReload.getRuleSnapshot());
        assertEquals(original.getEventId(), afterReload.getEventId());
        assertEquals(original.getCorrelationId(), afterReload.getCorrelationId());
        assertEquals(original.getEvaluationEvidence(), afterReload.getEvaluationEvidence());
        // Postgres timestamp columns round/truncate sub-millisecond
        // precision (observed even at microsecond truncation, due to
        // driver-level rounding at the boundary), so an Instant
        // round-tripped through the database may differ by a sub-millisecond
        // amount even when otherwise genuinely unchanged. Millisecond
        // precision is more than sufficient to prove no mutation occurred.
        assertEquals(original.getMatchedAt().truncatedTo(ChronoUnit.MILLIS), afterReload.getMatchedAt().truncatedTo(ChronoUnit.MILLIS));
        assertEquals(original.getMessage(), afterReload.getMessage());
        assertTrue(ruleMatchRepository.count() == 1, "AI failure must not create, duplicate, or delete any RuleMatch row");
    }

    @Test
    void providerUnavailable_persistedMatchStillIntact_afterEmptyResponse() {
        DomainEvent event = domainEventRepository.save(new DomainEvent(
                UUID.randomUUID(), EventType.TWIN_UPDATED, null, Instant.now(), "test",
                Map.of("value", 22.1), "INFO", "corr-containment-empty"));

        UUID matchId = UUID.randomUUID();
        ruleMatchRepository.save(new RuleMatchEntity(
                matchId, UUID.randomUUID(), richRuleSnapshot("Empty Response Containment Rule", "20.0"), event.eventId(),
                "corr-containment-empty", richEvidence(22.1), Instant.now(), "All conditions met"));

        AiReasoningBoundary emptyBoundary = ctx -> Optional.empty();
        GroundingChecker groundingChecker = new GroundingChecker(objectMapper);
        DeterministicExplanationRenderer renderer = new DeterministicExplanationRenderer();
        RuleMatchExplanationService service = new RuleMatchExplanationService(
                contextBuilder, new AiExplanationContextMapper(), emptyBoundary,
                new AiCandidateParser(objectMapper), groundingChecker, renderer, true);

        RuleMatchExplanation explanation = service.explain(matchId);

        assertEquals("DETERMINISTIC_FALLBACK", explanation.generatedBy());
        assertTrue(ruleMatchRepository.findById(matchId).isPresent(), "persisted match must still exist after a provider-unavailable outcome");
        assertEquals(1, ruleMatchRepository.count());
    }
}
