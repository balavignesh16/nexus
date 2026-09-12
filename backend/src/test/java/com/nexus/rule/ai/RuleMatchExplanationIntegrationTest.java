package com.nexus.rule.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.DomainEventRepository;
import com.nexus.event.domain.EventType;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end deterministic chain (Tier 4 Phase 1): real TimescaleDB ->
 * RuleMatchExplanationContextBuilder -> DeterministicExplanationRenderer ->
 * GroundingChecker. No mocks anywhere in the persistence chain. Reuses the
 * same Testcontainers pattern already established by
 * RuleRegistryPersistenceIntegrationTest/RuleMatchRepositoryIntegrationTest.
 * Restart survival is not re-tested here - already proven at the
 * persistence layer in Phase 9B/9C/11; this phase's context builder adds
 * no new state of its own.
 */
@SpringBootTest
@Testcontainers
class RuleMatchExplanationIntegrationTest {

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
    private DeterministicExplanationRenderer renderer;
    private GroundingChecker groundingChecker;

    @BeforeEach
    void setUp() {
        actionHistoryRepository.deleteAll();
        ruleMatchRepository.deleteAll();
        domainEventRepository.deleteAll();

        contextBuilder = new RuleMatchExplanationContextBuilder(ruleMatchRepository, domainEventRepository, actionHistoryRepository);
        renderer = new DeterministicExplanationRenderer();
        groundingChecker = new GroundingChecker(objectMapper);
    }

    private Map<String, Object> richRuleSnapshot(String name, String expectedValue) {
        return Map.of(
                "ruleId", UUID.randomUUID().toString(),
                "name", name,
                "enabled", true,
                "priority", 1,
                "conditions", List.of(Map.of("field", "VALUE", "operator", "GT", "expectedValue", expectedValue)),
                "actions", List.of(Map.of("actionType", "CREATE_ALERT", "parameters", Map.of())),
                "metadata", Map.of()
        );
    }

    private Map<String, Object> richEvidence(double actualValue) {
        return Map.of(
                "outcome", "matched",
                "evaluatedConditions", List.of(
                        Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0",
                                "actualValue", actualValue, "conditionMet", true)
                )
        );
    }

    @Test
    void fullDeterministicChain_realDatabase_valueGt30_actualValue34_7() {
        UUID ruleId = UUID.randomUUID();
        // device_id carries a real FK to devices on both domain_events and
        // action_history; left null here (device.id() itself is nullable on
        // both tables) to avoid provisioning a full site/building/space/device
        // chain for this test - deviceId RESOLUTION (reading it off a real,
        // non-null domain event) is already proven against real content in
        // RuleMatchExplanationContextBuilderTest's mocked deviceResolution
        // test; this test's job is the full real-DB JSONB/grounding chain.
        DomainEvent event = domainEventRepository.save(new DomainEvent(
                UUID.randomUUID(), EventType.TWIN_UPDATED, null, Instant.now(), "test",
                Map.of("value", 34.7), "INFO", "corr-e2e"));

        UUID matchId = UUID.randomUUID();
        RuleMatchEntity match = new RuleMatchEntity(
                matchId, ruleId, richRuleSnapshot("High Temperature Alert", "30.0"), event.eventId(),
                "corr-e2e", richEvidence(34.7), Instant.now(), "All conditions met");
        ruleMatchRepository.save(match);

        UUID requestId = UUID.randomUUID();
        actionHistoryRepository.save(new ActionHistoryRecord(
                requestId, matchId, null, ActionType.CREATE_ALERT, ExecutionStatus.SUCCESS,
                Map.of(), Instant.now(), "Alert created successfully (stub)"));

        // real DB -> ContextBuilder -> DeterministicRenderer -> GroundingChecker
        RuleMatchExplanationContext context = contextBuilder.build(matchId);
        RuleMatchExplanation candidate = renderer.render(context);
        GroundingResult result = groundingChecker.check(context, candidate);

        assertEquals(0, result.rejectedClaimCount(), "every deterministic claim must pass grounding: " + result.rejectionReasons());

        // Persisted rule snapshot is used.
        assertEquals("High Temperature Alert", context.ruleSnapshot().get("name"));

        // Evidence is preserved and numeric actualValue remains numeric.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evaluatedConditions =
                (List<Map<String, Object>>) context.evaluationEvidence().get("evaluatedConditions");
        Object actualValue = evaluatedConditions.get(0).get("actualValue");
        assertTrue(actualValue instanceof Number, "actualValue must remain numeric, not a string");
        assertEquals(34.7, ((Number) actualValue).doubleValue());

        // deviceId is resolved via the real linked domain event (here null,
        // since the event itself carries no device - the resolution
        // mechanism itself, with a populated device, is covered in
        // RuleMatchExplanationContextBuilderTest).
        assertEquals(event.deviceId(), context.deviceId());

        // action history is resolved.
        assertEquals(1, context.actions().size());
        assertEquals("SUCCESS", context.actions().get(0).status());

        // evidenceRefs validate (already asserted via rejectedClaimCount == 0 above);
        // also confirm the observed value is actually stated in the explanation.
        assertTrue(result.validated().claims().stream().anyMatch(c -> c.text().contains("34.7")));

        // fixed action disclaimer is present.
        assertEquals(DeterministicExplanationRenderer.ACTION_DISCLAIMER, result.validated().actionNote());

        // no physical action claim is produced.
        for (Claim claim : result.validated().claims()) {
            String lower = claim.text().toLowerCase();
            assertFalse(lower.contains("physically") || lower.contains("actuated"));
        }
    }

    @Test
    void ruleEditAfterMatch_contextStillReportsOriginalSnapshot() {
        UUID ruleId = UUID.randomUUID();
        DomainEvent event = domainEventRepository.save(new DomainEvent(
                UUID.randomUUID(), EventType.TWIN_UPDATED, null, Instant.now(), "test",
                Map.of("value", 34.7), "INFO", "corr-edit"));

        UUID matchId = UUID.randomUUID();
        ruleMatchRepository.save(new RuleMatchEntity(
                matchId, ruleId, richRuleSnapshot("Edit Integrity Rule", "30.0"), event.eventId(),
                "corr-edit", richEvidence(34.7), Instant.now(), "All conditions met"));

        // The rule_matches row's own snapshot is immutable once written - there
        // is no live RuleRegistry involved in this test at all, which is
        // itself the point: the context builder never consults it.
        RuleMatchExplanationContext context = contextBuilder.build(matchId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conditions = (List<Map<String, Object>>) context.ruleSnapshot().get("conditions");
        assertEquals("30.0", conditions.get(0).get("expectedValue"),
                "context must reflect the persisted historical snapshot, never a live rule");
    }

    @Test
    void missingDomainEvent_deviceIdNull_explanationStillGrounded() {
        UUID matchId = UUID.randomUUID();
        UUID nonExistentEventId = UUID.randomUUID();
        ruleMatchRepository.save(new RuleMatchEntity(
                matchId, UUID.randomUUID(), richRuleSnapshot("Expired Event Rule", "30.0"), nonExistentEventId,
                "corr-expired", richEvidence(34.7), Instant.now(), "All conditions met"));

        RuleMatchExplanationContext context = contextBuilder.build(matchId);
        assertNull(context.deviceId());
        assertNull(context.domainEvent());

        RuleMatchExplanation candidate = renderer.render(context);
        GroundingResult result = groundingChecker.check(context, candidate);

        assertEquals(0, result.rejectedClaimCount());
        assertTrue(candidate.claims().stream().anyMatch(c ->
                c.tier() == ClaimTier.UNKNOWN && c.text().equals(DeterministicExplanationRenderer.DEVICE_UNKNOWN)));
    }

    // ---------- Tier 4 Phase 2A: fake AI candidate against real persisted evidence ----------

    @Test
    void fakeAiCandidate_deliberatelyIncorrectAssertedValue_rejectedByRealGroundingChecker() {
        UUID ruleId = UUID.randomUUID();
        DomainEvent event = domainEventRepository.save(new DomainEvent(
                UUID.randomUUID(), EventType.TWIN_UPDATED, null, Instant.now(), "test",
                Map.of("value", 34.7), "INFO", "corr-fake-ai"));

        UUID matchId = UUID.randomUUID();
        ruleMatchRepository.save(new RuleMatchEntity(
                matchId, ruleId, richRuleSnapshot("Fake AI Rule", "30.0"), event.eventId(),
                "corr-fake-ai", richEvidence(34.7), Instant.now(), "All conditions met"));
        actionHistoryRepository.save(new ActionHistoryRecord(
                UUID.randomUUID(), matchId, null, ActionType.CREATE_ALERT, ExecutionStatus.SUCCESS,
                Map.of(), Instant.now(), "Alert created successfully (stub)"));

        RuleMatchExplanationContext context = contextBuilder.build(matchId);
        AiExplanationContext aiContext = new AiExplanationContextMapper().toAiContext(context);

        // A fake AI candidate citing real evidence but asserting the wrong value.
        AiReasoningBoundary fakeBoundary = new FakeAiReasoningBoundary(ctx -> new RuleMatchExplanation(
                matchId, "The observed value was 40.2, exceeding the threshold.",
                List.of(new Claim(ClaimTier.FACT, "Observed value was 40.2.",
                        "evaluationEvidence.evaluatedConditions[0].actualValue", 40.2, List.of())),
                null, "AI"));
        RuleMatchExplanation candidate = fakeBoundary.generate(aiContext);

        GroundingResult result = groundingChecker.check(context, candidate);

        assertEquals(1, result.rejectedClaimCount(), "the real, persisted actualValue is 34.7 - a 40.2 assertion must be rejected");
        assertTrue(result.validated().claims().isEmpty(), "zero surviving claims - caller must use the deterministic fallback");

        // Mandatory NEXUS safety claims remain available via the deterministic fallback path.
        RuleMatchExplanation fallback = renderer.render(context);
        assertTrue(fallback.claims().stream().anyMatch(c -> c.text().equals(DeterministicExplanationRenderer.CAUSAL_UNCERTAINTY)));
        assertEquals(DeterministicExplanationRenderer.ACTION_DISCLAIMER, fallback.actionNote());
    }

    @Test
    void fakeAiCandidate_correctAssertedValue_survivesAndCarriesMandatoryClaims() {
        UUID ruleId = UUID.randomUUID();
        DomainEvent event = domainEventRepository.save(new DomainEvent(
                UUID.randomUUID(), EventType.TWIN_UPDATED, null, Instant.now(), "test",
                Map.of("value", 34.7), "INFO", "corr-fake-ai-ok"));

        UUID matchId = UUID.randomUUID();
        ruleMatchRepository.save(new RuleMatchEntity(
                matchId, ruleId, richRuleSnapshot("Fake AI Rule OK", "30.0"), event.eventId(),
                "corr-fake-ai-ok", richEvidence(34.7), Instant.now(), "All conditions met"));

        RuleMatchExplanationContext context = contextBuilder.build(matchId);
        AiExplanationContext aiContext = new AiExplanationContextMapper().toAiContext(context);

        AiReasoningBoundary fakeBoundary = new FakeAiReasoningBoundary(ctx -> new RuleMatchExplanation(
                matchId, "The observed value was 34.7, exceeding the threshold.",
                List.of(new Claim(ClaimTier.FACT, "Observed value was 34.7.",
                        "evaluationEvidence.evaluatedConditions[0].actualValue", 34.7, List.of())),
                null, "AI"));
        RuleMatchExplanation candidate = fakeBoundary.generate(aiContext);

        GroundingResult result = groundingChecker.check(context, candidate);

        assertEquals(0, result.rejectedClaimCount());
        assertTrue(result.validated().claims().stream().anyMatch(c ->
                c.text().equals(DeterministicExplanationRenderer.CAUSAL_UNCERTAINTY)),
                "mandatory NEXUS safety claim must be present even for a successful AI explanation");
        assertEquals("AI", result.validated().generatedBy());
    }
}
