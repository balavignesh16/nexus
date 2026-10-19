package com.nexus.rule.ai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.DomainEventRepository;
import com.nexus.event.domain.EventType;
import com.nexus.rule.ai.AiExplanationContext;
import com.nexus.rule.ai.AiReasoningBoundary;
import com.nexus.rule.persistence.RuleMatchEntity;
import com.nexus.rule.persistence.RuleMatchRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP test for GET /api/v1/ai/explanations/rule-match/{matchId}
 * (Tier 4 Phase 2C): real TimescaleDB, real RuleMatchExplanationService,
 * real GroundingChecker, real DeterministicExplanationRenderer - only
 * AiReasoningBoundary is faked (via a @Primary test bean), so no live
 * OpenAI call is ever made. Rate limits are widened via
 * @DynamicPropertySource so this class's several requests never trip the
 * production defaults (5/minute), avoiding cross-test interference within
 * one Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AiExplanationControllerIntegrationTest {

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
        registry.add("nexus.ai.enabled", () -> "true");
        registry.add("nexus.ai.send-device-evidence", () -> "true");
        registry.add("nexus.ai.rate-limit.requests-per-minute", () -> "1000");
        registry.add("nexus.ai.rate-limit.burst", () -> "1000");
        registry.add("nexus.ai.rate-limit.concurrency-limit", () -> "50");
    }

    static final AtomicReference<Function<AiExplanationContext, Optional<String>>> FAKE_RESPONDER =
            new AtomicReference<>(ctx -> Optional.empty());

    @TestConfiguration
    static class FakeBoundaryConfig {
        @Bean
        @Primary
        AiReasoningBoundary fakeAiReasoningBoundary() {
            return ctx -> FAKE_RESPONDER.get().apply(ctx);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RuleMatchRepository ruleMatchRepository;
    @Autowired
    private DomainEventRepository domainEventRepository;
    @Autowired
    private ActionHistoryRepository actionHistoryRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        actionHistoryRepository.deleteAll();
        ruleMatchRepository.deleteAll();
        domainEventRepository.deleteAll();
        FAKE_RESPONDER.set(ctx -> Optional.empty());
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
                                "actualValue", actualValue, "conditionMet", true))
        );
    }

    private UUID persistMatch(String ruleName, double actualValue) {
        DomainEvent event = domainEventRepository.save(new DomainEvent(
                UUID.randomUUID(), EventType.TWIN_UPDATED, null, Instant.now(), "test",
                Map.of("value", actualValue), "INFO", "corr-endpoint"));
        UUID matchId = UUID.randomUUID();
        ruleMatchRepository.save(new RuleMatchEntity(
                matchId, UUID.randomUUID(), richRuleSnapshot(ruleName, "30.0"), event.eventId(),
                "corr-endpoint", richEvidence(actualValue), Instant.now(), "All conditions met"));
        return matchId;
    }

    private String candidateJson(UUID matchId, double actualValue) {
        return """
                {"matchId":"%s","summary":"s","claims":[{"tier":"FACT","text":"Observed value was %s.",
                "evidenceRef":"evaluationEvidence.evaluatedConditions[0].actualValue","assertedValue":%s,"supportingEvidenceRefs":[]}]}
                """.formatted(matchId, actualValue, actualValue);
    }

    @Test
    void persistedMatch_aiSuccess_returns200WithAiExplanation() throws Exception {
        UUID matchId = persistMatch("Endpoint AI Rule", 34.7);
        FAKE_RESPONDER.set(ctx -> Optional.of(candidateJson(matchId, 34.7)));

        mockMvc.perform(get("/api/v1/ai/explanations/rule-match/{matchId}", matchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchId").value(matchId.toString()))
                .andExpect(jsonPath("$.generatedBy").value("AI"))
                // P6-016: an AI explanation says which provider, model and (hashed) configuration wrote it
                .andExpect(jsonPath("$.provenance.providerId").value("openai-compatible"))
                .andExpect(jsonPath("$.provenance.modelId").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.provenance.configHash").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{16}")));
    }

    @Test
    void persistedMatch_providerFailure_returns200WithDeterministicFallback() throws Exception {
        UUID matchId = persistMatch("Endpoint Fallback Rule", 34.7);
        FAKE_RESPONDER.set(ctx -> {
            throw new RuntimeException("simulated provider failure");
        });

        mockMvc.perform(get("/api/v1/ai/explanations/rule-match/{matchId}", matchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchId").value(matchId.toString()))
                .andExpect(jsonPath("$.generatedBy").value("DETERMINISTIC_FALLBACK"))
                // the deterministic path says no model wrote it
                .andExpect(jsonPath("$.provenance.providerId").value("none"))
                .andExpect(jsonPath("$.provenance.modelId").value("none"));
    }

    @Test
    void unknownMatch_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/ai/explanations/rule-match/{matchId}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidUuid_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/ai/explanations/rule-match/{matchId}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void responseNeverLeaksProviderErrorDetail() throws Exception {
        UUID matchId = persistMatch("Endpoint Leak Rule", 34.7);
        FAKE_RESPONDER.set(ctx -> {
            throw new RuntimeException("SECRET_PROVIDER_INTERNAL_DETAIL_should_never_leak");
        });

        mockMvc.perform(get("/api/v1/ai/explanations/rule-match/{matchId}", matchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedBy").value("DETERMINISTIC_FALLBACK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(Matchers.not(Matchers.containsString("SECRET_PROVIDER_INTERNAL_DETAIL"))));
    }

    @Test
    void historicalSnapshot_reflectedInResponse_endToEndThroughHttp() throws Exception {
        UUID matchId = persistMatch("Historical Snapshot Rule A", 34.7);
        FAKE_RESPONDER.set(ctx -> Optional.empty());

        mockMvc.perform(get("/api/v1/ai/explanations/rule-match/{matchId}", matchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(Matchers.containsString("Historical Snapshot Rule A")));
    }
}
