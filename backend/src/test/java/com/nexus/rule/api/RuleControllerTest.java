package com.nexus.rule.api;

import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleOperator;
import com.nexus.rule.domain.RuleAction;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private RuleRegistry ruleRegistry;
    private RuleController controller;

    @BeforeEach
    void setUp() {
        ruleRegistry = mock(RuleRegistry.class);
        BoundedRuleMatchStore matchStore = mock(BoundedRuleMatchStore.class);
        ruleMatchRepository = mock(RuleMatchRepository.class);
        controller = new RuleController(ruleRegistry, matchStore, ruleMatchRepository);
    }

    private Rule ruleWithConditions(String name, List<RuleCondition> conditions) {
        return new Rule(UUID.randomUUID(), name, true, 1, conditions,
                List.of(new RuleAction("CREATE_ALERT", Map.of())), Map.of());
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
                controller.getRuleMatchHistory(100, null, null, null);

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
                controller.getRuleMatchHistory(100, ruleId, null, null);

        assertEquals(1, response.getBody().size());
        verify(ruleMatchRepository).findByRuleId(eq(ruleId), any(Pageable.class));
    }

    @Test
    void getRuleMatchHistory_correlationIdFilter_usesFindByCorrelationId() {
        RuleMatchEntity entity = sampleEntity(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), "corr-3");
        when(ruleMatchRepository.findByCorrelationId(eq("corr-3"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity)));

        ResponseEntity<List<com.nexus.rule.api.dto.RuleMatchSummaryResponse>> response =
                controller.getRuleMatchHistory(100, null, "corr-3", null);

        assertEquals(1, response.getBody().size());
        verify(ruleMatchRepository).findByCorrelationId(eq("corr-3"), any(Pageable.class));
    }

    @Test
    void getRuleMatchHistory_zeroLimit_rejected() {
        assertThrows(IllegalArgumentException.class, () -> controller.getRuleMatchHistory(0, null, null, null));
    }

    @Test
    void getRuleMatchHistory_negativeLimit_rejected() {
        assertThrows(IllegalArgumentException.class, () -> controller.getRuleMatchHistory(-1, null, null, null));
    }

    @Test
    void getRuleMatchHistory_limitOverMax_rejected() {
        assertThrows(IllegalArgumentException.class, () -> controller.getRuleMatchHistory(501, null, null, null));
    }

    @Test
    void getRuleMatchHistory_limitAtMax_accepted() {
        when(ruleMatchRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        assertEquals(200, controller.getRuleMatchHistory(500, null, null, null).getStatusCode().value());
    }

    @Test
    void getRuleMatchHistory_deviceIdFilter_usesFindByDeviceId() {
        UUID deviceA = UUID.randomUUID();
        RuleMatchEntity entity = sampleEntity(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), "corr-4");
        when(ruleMatchRepository.findByDeviceId(eq(deviceA), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity)));

        ResponseEntity<List<com.nexus.rule.api.dto.RuleMatchSummaryResponse>> response =
                controller.getRuleMatchHistory(100, null, null, deviceA);

        assertEquals(1, response.getBody().size());
        verify(ruleMatchRepository).findByDeviceId(eq(deviceA), any(Pageable.class));
        verify(ruleMatchRepository, org.mockito.Mockito.never()).findAll(any(Pageable.class));
    }

    @Test
    void getRuleMatchHistory_deviceIdWithNoMatches_returnsEmptyListNotError() {
        UUID unknownDevice = UUID.randomUUID();
        when(ruleMatchRepository.findByDeviceId(eq(unknownDevice), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<List<com.nexus.rule.api.dto.RuleMatchSummaryResponse>> response =
                controller.getRuleMatchHistory(100, null, null, unknownDevice);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, response.getBody().size());
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

    // ---------- GET /rules device-scoping filter ----------

    @Test
    void listRules_noDeviceIdFilter_returnsEveryRule() {
        String deviceA = UUID.randomUUID().toString();
        Rule scoped = ruleWithConditions("Scoped", List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, deviceA)));
        Rule platformWide = ruleWithConditions("Platform-wide", List.of(new RuleCondition(RuleField.SEVERITY, RuleOperator.EQ, "CRITICAL")));
        when(ruleRegistry.list()).thenReturn(List.of(scoped, platformWide));

        ResponseEntity<List<com.nexus.rule.api.dto.RuleResponse>> response = controller.listRules(null);

        assertEquals(2, response.getBody().size());
    }

    @Test
    void listRules_deviceIdFilter_returnsOnlyRulesReferencingThatDevice() {
        String deviceA = UUID.randomUUID().toString();
        String deviceB = UUID.randomUUID().toString();
        Rule ruleForA = ruleWithConditions("For A", List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, deviceA)));
        Rule ruleForB = ruleWithConditions("For B", List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, deviceB)));
        when(ruleRegistry.list()).thenReturn(List.of(ruleForA, ruleForB));

        ResponseEntity<List<com.nexus.rule.api.dto.RuleResponse>> response = controller.listRules(deviceA);

        assertEquals(1, response.getBody().size());
        assertEquals("For A", response.getBody().get(0).name());
    }

    @Test
    void listRules_deviceIdFilter_excludesPlatformWideRulesWithNoDeviceIdCondition() {
        String deviceA = UUID.randomUUID().toString();
        Rule ruleForA = ruleWithConditions("For A", List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, deviceA)));
        Rule platformWide = ruleWithConditions("Platform-wide", List.of(new RuleCondition(RuleField.SEVERITY, RuleOperator.EQ, "CRITICAL")));
        when(ruleRegistry.list()).thenReturn(List.of(ruleForA, platformWide));

        ResponseEntity<List<com.nexus.rule.api.dto.RuleResponse>> response = controller.listRules(deviceA);

        assertEquals(1, response.getBody().size());
        assertTrue(response.getBody().stream().noneMatch(r -> r.name().equals("Platform-wide")));
    }

    @Test
    void listRules_blankDeviceIdParam_rejectedAsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> controller.listRules("   "));
    }

    @Test
    void listRules_deviceIdWithNoMatchingRules_returnsEmptyListNotError() {
        String unknownDeviceId = UUID.randomUUID().toString();
        Rule ruleForOtherDevice = ruleWithConditions("For Other", List.of(
                new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, UUID.randomUUID().toString())));
        when(ruleRegistry.list()).thenReturn(List.of(ruleForOtherDevice));

        ResponseEntity<List<com.nexus.rule.api.dto.RuleResponse>> response = controller.listRules(unknownDeviceId);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void listRules_deviceIdFilter_matchesOnAnyOfMultipleConditions() {
        String deviceA = UUID.randomUUID().toString();
        Rule ruleForA = ruleWithConditions("Multi-condition", List.of(
                new RuleCondition(RuleField.SEVERITY, RuleOperator.EQ, "CRITICAL"),
                new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, deviceA)));
        when(ruleRegistry.list()).thenReturn(List.of(ruleForA));

        ResponseEntity<List<com.nexus.rule.api.dto.RuleResponse>> response = controller.listRules(deviceA);

        assertEquals(1, response.getBody().size());
    }
}
