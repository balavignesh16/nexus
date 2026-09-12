package com.nexus.rule.api;

import com.nexus.rule.persistence.BoundedRuleMatchStore;
import com.nexus.rule.persistence.RuleMatchEntity;
import com.nexus.rule.persistence.RuleMatchRepository;
import com.nexus.rule.persistence.RuleRegistry;
import com.nexus.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for RuleController's durable rule-match history endpoints
 * (Tier 3 Phase 11). RuleRegistry/BoundedRuleMatchStore are mocked and
 * unused by these tests - they exercise only the new
 * getRuleMatchHistory/getRuleMatchDetail methods, which read exclusively
 * from a mocked RuleMatchRepository. Real database behavior (JSONB content,
 * restart survival, rule-edit snapshot integrity) is covered separately in
 * RuleRegistryPersistenceIntegrationTest.
 */
class RuleControllerTest {

    private RuleMatchRepository ruleMatchRepository;
    private RuleController controller;

    @BeforeEach
    void setUp() {
        RuleRegistry ruleRegistry = mock(RuleRegistry.class);
        BoundedRuleMatchStore matchStore = mock(BoundedRuleMatchStore.class);
        ruleMatchRepository = mock(RuleMatchRepository.class);
        controller = new RuleController(ruleRegistry, matchStore, ruleMatchRepository);
    }

    private RuleMatchEntity sampleEntity(UUID matchId, UUID ruleId, Instant matchedAt, String correlationId) {
        return new RuleMatchEntity(
                matchId, ruleId,
                Map.of("name", "Test Rule", "conditions", List.of(Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0"))),
                UUID.randomUUID(), correlationId,
                Map.of("outcome", "matched", "evaluatedConditions", List.of(Map.of("field", "VALUE", "actualValue", 34.7, "conditionMet", true))),
                matchedAt, "All conditions met"
        );
    }

    @Test
    void getRuleMatchHistory_noFilters_returnsAllViaFindAll() {
        RuleMatchEntity entity = sampleEntity(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), "corr-1");
        when(ruleMatchRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity)));

        ResponseEntity<List<com.nexus.rule.api.dto.RuleMatchSummaryResponse>> response =
                controller.getRuleMatchHistory(100, null, null);

        assertEquals(1, response.getBody().size());
        assertEquals(entity.getMatchId(), response.getBody().get(0).matchId());
        assertEquals("Test Rule", response.getBody().get(0).ruleName());
    }

    @Test
    void getRuleMatchHistory_ruleIdFilter_usesFindByRuleId() {
        UUID ruleId = UUID.randomUUID();
        RuleMatchEntity entity = sampleEntity(UUID.randomUUID(), ruleId, Instant.now(), "corr-2");
        when(ruleMatchRepository.findByRuleId(eq(ruleId), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity)));

        ResponseEntity<List<com.nexus.rule.api.dto.RuleMatchSummaryResponse>> response =
                controller.getRuleMatchHistory(100, ruleId, null);

        assertEquals(1, response.getBody().size());
        verify(ruleMatchRepository).findByRuleId(eq(ruleId), any(Pageable.class));
    }

    @Test
    void getRuleMatchHistory_correlationIdFilter_usesFindByCorrelationId() {
        RuleMatchEntity entity = sampleEntity(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), "corr-3");
        when(ruleMatchRepository.findByCorrelationId(eq("corr-3"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity)));

        ResponseEntity<List<com.nexus.rule.api.dto.RuleMatchSummaryResponse>> response =
                controller.getRuleMatchHistory(100, null, "corr-3");

        assertEquals(1, response.getBody().size());
        verify(ruleMatchRepository).findByCorrelationId(eq("corr-3"), any(Pageable.class));
    }

    @Test
    void getRuleMatchHistory_zeroLimit_rejected() {
        assertThrows(IllegalArgumentException.class, () -> controller.getRuleMatchHistory(0, null, null));
    }

    @Test
    void getRuleMatchHistory_negativeLimit_rejected() {
        assertThrows(IllegalArgumentException.class, () -> controller.getRuleMatchHistory(-1, null, null));
    }

    @Test
    void getRuleMatchHistory_limitOverMax_rejected() {
        assertThrows(IllegalArgumentException.class, () -> controller.getRuleMatchHistory(501, null, null));
    }

    @Test
    void getRuleMatchHistory_limitAtMax_accepted() {
        when(ruleMatchRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        assertEquals(200, controller.getRuleMatchHistory(500, null, null).getStatusCode().value());
    }

    @Test
    void getRuleMatchDetail_found_returnsFullRecord() {
        UUID matchId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        RuleMatchEntity entity = new RuleMatchEntity(
                matchId, UUID.randomUUID(),
                Map.of("name", "Detail Rule", "conditions", List.of(Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30.0"))),
                eventId, "corr-detail",
                Map.of("outcome", "matched", "evaluatedConditions", List.of(Map.of("field", "VALUE", "actualValue", 34.7, "conditionMet", true))),
                Instant.now(), "All conditions met"
        );
        when(ruleMatchRepository.findById(matchId)).thenReturn(Optional.of(entity));

        ResponseEntity<com.nexus.rule.api.dto.RuleMatchDetailResponse> response = controller.getRuleMatchDetail(matchId);

        assertEquals(matchId, response.getBody().matchId());
        assertEquals(eventId, response.getBody().eventId());
        assertEquals("corr-detail", response.getBody().correlationId());
        assertEquals("Detail Rule", response.getBody().ruleSnapshot().get("name"));
        assertEquals(34.7, ((Map<?, ?>) ((List<?>) response.getBody().evaluationEvidence().get("evaluatedConditions")).get(0)).get("actualValue"));
    }

    @Test
    void getRuleMatchDetail_notFound_throwsResourceNotFoundException() {
        UUID matchId = UUID.randomUUID();
        when(ruleMatchRepository.findById(matchId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> controller.getRuleMatchDetail(matchId));
    }
}
