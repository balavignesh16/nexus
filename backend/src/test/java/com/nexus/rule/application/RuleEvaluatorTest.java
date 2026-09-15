package com.nexus.rule.application;

import com.nexus.device.capability.CapabilityType;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.rule.domain.ConditionEvidence;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleOperator;
import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.DigitalTwinRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behavioral tests for RuleEvaluator: every RuleOperator (EQ, NE, GT, LT, GTE,
 * LTE), the RuleField -> DomainEvent extraction logic, and the documented edge
 * cases (missing conditions, null/missing values, non-numeric comparisons,
 * multi-condition AND semantics). The legacy (capabilityType == null) path
 * needs no DigitalTwinRegistry interaction at all - a mock is used only so
 * the constructor is satisfiable; capability-aware tests below configure it
 * explicitly with an in-memory fake.
 */
class RuleEvaluatorTest {

    private DigitalTwinRegistry digitalTwinRegistry;
    private RuleEvaluator evaluator;

    @BeforeEach
    void setUp() {
        digitalTwinRegistry = mock(DigitalTwinRegistry.class);
        evaluator = new RuleEvaluator(digitalTwinRegistry);
    }

    private DomainEvent eventWithPayload(EventType type, UUID deviceId, String severity, Map<String, Object> payload) {
        return new DomainEvent(UUID.randomUUID(), type, deviceId, Instant.now(), "test", payload, severity, "corr-1");
    }

    private Rule ruleWithCondition(RuleField field, RuleOperator operator, String expectedValue) {
        RuleCondition condition = new RuleCondition(field, operator, expectedValue);
        return new Rule(UUID.randomUUID(), "Test Rule", true, 1, List.of(condition), List.of(), Map.of());
    }

    private Rule ruleWithConditions(RuleCondition... conditions) {
        return new Rule(UUID.randomUUID(), "Test Rule", true, 1, List.of(conditions), List.of(), Map.of());
    }

    // ---------- EQ ----------

    @Test
    void eq_matchingValue_matches() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of());
        Rule rule = ruleWithCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED");

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertTrue(result.matched());
    }

    @Test
    void eq_differentValue_doesNotMatch() {
        DomainEvent event = eventWithPayload(EventType.SENSOR_VALUE_CHANGED, UUID.randomUUID(), "INFO", Map.of());
        Rule rule = ruleWithCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED");

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertFalse(result.matched());
    }

    // ---------- NE ----------

    @Test
    void ne_differentValue_matches() {
        DomainEvent event = eventWithPayload(EventType.SENSOR_VALUE_CHANGED, UUID.randomUUID(), "INFO", Map.of());
        Rule rule = ruleWithCondition(RuleField.EVENT_TYPE, RuleOperator.NE, "TWIN_UPDATED");

        assertTrue(evaluator.evaluate(rule, event).matched());
    }

    @Test
    void ne_sameValue_doesNotMatch() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of());
        Rule rule = ruleWithCondition(RuleField.EVENT_TYPE, RuleOperator.NE, "TWIN_UPDATED");

        assertFalse(evaluator.evaluate(rule, event).matched());
    }

    // ---------- GT ----------

    @Test
    void gt_actualAboveExpected_matches() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 35.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.GT, "30.0");

        assertTrue(evaluator.evaluate(rule, event).matched());
    }

    @Test
    void gt_actualBelowExpected_doesNotMatch() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 25.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.GT, "30.0");

        assertFalse(evaluator.evaluate(rule, event).matched());
    }

    @Test
    void gt_actualEqualToExpected_doesNotMatch_boundaryCase() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 30.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.GT, "30.0");

        assertFalse(evaluator.evaluate(rule, event).matched(), "GT must be strict, equal values must not match");
    }

    // ---------- LT ----------

    @Test
    void lt_actualBelowExpected_matches() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 25.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.LT, "30.0");

        assertTrue(evaluator.evaluate(rule, event).matched());
    }

    @Test
    void lt_actualAboveExpected_doesNotMatch() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 35.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.LT, "30.0");

        assertFalse(evaluator.evaluate(rule, event).matched());
    }

    @Test
    void lt_actualEqualToExpected_doesNotMatch_boundaryCase() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 30.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.LT, "30.0");

        assertFalse(evaluator.evaluate(rule, event).matched(), "LT must be strict, equal values must not match");
    }

    // ---------- GTE ----------

    @Test
    void gte_actualAboveExpected_matches() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 35.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.GTE, "30.0");

        assertTrue(evaluator.evaluate(rule, event).matched());
    }

    @Test
    void gte_actualEqualToExpected_matches_boundaryCase() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 30.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.GTE, "30.0");

        assertTrue(evaluator.evaluate(rule, event).matched(), "GTE must include the equal-value boundary, unlike GT");
    }

    @Test
    void gte_actualBelowExpected_doesNotMatch() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 25.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.GTE, "30.0");

        assertFalse(evaluator.evaluate(rule, event).matched());
    }

    // ---------- LTE ----------

    @Test
    void lte_actualBelowExpected_matches() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 25.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.LTE, "30.0");

        assertTrue(evaluator.evaluate(rule, event).matched());
    }

    @Test
    void lte_actualEqualToExpected_matches_boundaryCase() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 30.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.LTE, "30.0");

        assertTrue(evaluator.evaluate(rule, event).matched(), "LTE must include the equal-value boundary, unlike LT");
    }

    @Test
    void lte_actualAboveExpected_doesNotMatch() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 35.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.LTE, "30.0");

        assertFalse(evaluator.evaluate(rule, event).matched());
    }

    // ---------- Field extraction ----------

    @Test
    void deviceIdField_matchingDevice_matches() {
        UUID deviceId = UUID.randomUUID();
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, deviceId, "INFO", Map.of());
        Rule rule = ruleWithCondition(RuleField.DEVICE_ID, RuleOperator.EQ, deviceId.toString());

        assertTrue(evaluator.evaluate(rule, event).matched());
    }

    @Test
    void deviceIdField_differentDevice_doesNotMatch() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of());
        Rule rule = ruleWithCondition(RuleField.DEVICE_ID, RuleOperator.EQ, UUID.randomUUID().toString());

        assertFalse(evaluator.evaluate(rule, event).matched());
    }

    @Test
    void severityField_matchingSeverity_matches() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "CRITICAL", Map.of());
        Rule rule = ruleWithCondition(RuleField.SEVERITY, RuleOperator.EQ, "CRITICAL");

        assertTrue(evaluator.evaluate(rule, event).matched());
    }

    @Test
    void sensorTypeField_readsFromPayload() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO",
                Map.of("sensorType", "GAS_SENSOR"));
        Rule rule = ruleWithCondition(RuleField.SENSOR_TYPE, RuleOperator.EQ, "GAS_SENSOR");

        assertTrue(evaluator.evaluate(rule, event).matched());
    }

    @Test
    void valueField_prefersCurrentValueOverValue_whenBothPresent() {
        // extractActualValue explicitly checks "currentValue" (used by
        // SENSOR_VALUE_CHANGED events) before falling back to "value" (used by
        // TWIN_UPDATED/TWIN_CREATED events). This is real, specific precedence
        // behavior worth locking in with a test.
        DomainEvent event = eventWithPayload(EventType.SENSOR_VALUE_CHANGED, UUID.randomUUID(), "INFO",
                Map.of("currentValue", 99.0, "value", 1.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.EQ, "99.0");

        assertTrue(evaluator.evaluate(rule, event).matched());
    }

    @Test
    void valueField_fallsBackToValue_whenCurrentValueAbsent() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO",
                Map.of("value", 42.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.EQ, "42.0");

        assertTrue(evaluator.evaluate(rule, event).matched());
    }

    // ---------- Edge cases ----------

    @Test
    void noConditions_neverMatches() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of());
        Rule rule = new Rule(UUID.randomUUID(), "Empty Rule", true, 1, List.of(), List.of(), Map.of());

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertFalse(result.matched());
        assertEquals("No conditions defined", result.message());
    }

    @Test
    void missingPayloadKey_isTreatedAsNoMatch_notAnError() {
        // SENSOR_TYPE condition against an event whose payload has no
        // "sensorType" key at all - extractActualValue returns null, and
        // evaluateCondition short-circuits to false rather than throwing.
        DomainEvent event = eventWithPayload(EventType.TWIN_CREATED, UUID.randomUUID(), "INFO", Map.of());
        Rule rule = ruleWithCondition(RuleField.SENSOR_TYPE, RuleOperator.EQ, "GAS_SENSOR");

        assertFalse(evaluator.evaluate(rule, event).matched());
    }

    @Test
    void nonNumericActualValue_forNumericOperator_isTreatedAsNoMatch_notAnError() {
        // GT/LT/GTE/LTE parse both sides as Double; a NumberFormatException
        // is caught internally and treated as "condition not met", not
        // propagated as an exception.
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO",
                Map.of("value", "not-a-number"));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.GT, "30.0");

        assertFalse(evaluator.evaluate(rule, event).matched());
    }

    @Test
    void multipleConditions_allMatch_ruleMatches() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 35.0));
        Rule rule = ruleWithConditions(
                new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"),
                new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30.0")
        );

        assertTrue(evaluator.evaluate(rule, event).matched());
    }

    @Test
    void multipleConditions_oneFails_ruleDoesNotMatch_andReportsIt() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 10.0));
        Rule rule = ruleWithConditions(
                new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"),
                new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30.0")
        );

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertFalse(result.matched());
        assertTrue(result.message().contains("VALUE"), "failure message should identify which condition failed");
    }

    // ---------- Tier 3 Phase 9B: evaluation evidence ----------

    @Test
    void evidence_valueGtMatch_recordsActualExpectedAndMet() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 35.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.GT, "30.0");

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertEquals(1, result.evidence().size());
        ConditionEvidence evidence = result.evidence().get(0);
        assertEquals(RuleField.VALUE, evidence.field());
        assertEquals(RuleOperator.GT, evidence.operator());
        assertEquals("30.0", evidence.expectedValue());
        assertEquals(35.0, evidence.actualValue());
        assertTrue(evidence.conditionMet());
    }

    @Test
    void evidence_valueGtNonMatch_recordsConditionNotMet() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 25.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.GT, "30.0");

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertEquals(1, result.evidence().size());
        ConditionEvidence evidence = result.evidence().get(0);
        assertEquals(25.0, evidence.actualValue());
        assertFalse(evidence.conditionMet());
    }

    @Test
    void evidence_eqOperator_recordsActualExpectedAndMet() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of());
        Rule rule = ruleWithCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED");

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        ConditionEvidence evidence = result.evidence().get(0);
        assertEquals(RuleOperator.EQ, evidence.operator());
        assertEquals("TWIN_UPDATED", evidence.expectedValue());
        assertEquals("TWIN_UPDATED", evidence.actualValue());
        assertTrue(evidence.conditionMet());
    }

    @Test
    void evidence_sensorType_recordsActualValueFromPayload() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO",
                Map.of("sensorType", "GAS_SENSOR"));
        Rule rule = ruleWithCondition(RuleField.SENSOR_TYPE, RuleOperator.EQ, "GAS_SENSOR");

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        ConditionEvidence evidence = result.evidence().get(0);
        assertEquals(RuleField.SENSOR_TYPE, evidence.field());
        assertEquals("GAS_SENSOR", evidence.actualValue());
        assertTrue(evidence.conditionMet());
    }

    @Test
    void evidence_multipleConditionsAllMatch_recordsEveryConditionInOrder() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 35.0));
        Rule rule = ruleWithConditions(
                new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"),
                new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30.0")
        );

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertTrue(result.matched());
        assertEquals(2, result.evidence().size());
        assertEquals(RuleField.EVENT_TYPE, result.evidence().get(0).field());
        assertEquals(RuleField.VALUE, result.evidence().get(1).field());
        assertTrue(result.evidence().get(0).conditionMet());
        assertTrue(result.evidence().get(1).conditionMet());
    }

    @Test
    void evidence_multipleConditionsFirstFails_shortCircuitsAndDoesNotEvaluateLaterConditions() {
        // EVENT_TYPE fails first; VALUE must never be evaluated, so evidence
        // must contain exactly one entry, not two.
        DomainEvent event = eventWithPayload(EventType.SENSOR_VALUE_CHANGED, UUID.randomUUID(), "INFO", Map.of("value", 35.0));
        Rule rule = ruleWithConditions(
                new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"),
                new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30.0")
        );

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertFalse(result.matched());
        assertEquals(1, result.evidence().size(), "evaluation must short-circuit at the first failed condition");
        assertEquals(RuleField.EVENT_TYPE, result.evidence().get(0).field());
        assertFalse(result.evidence().get(0).conditionMet());
    }

    @Test
    void evidence_missingValue_recordsNullActualValueAndNotMet() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of());
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.GT, "30.0");

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        ConditionEvidence evidence = result.evidence().get(0);
        assertNull(evidence.actualValue());
        assertFalse(evidence.conditionMet());
    }

    @Test
    void evidence_missingSensorType_recordsNullActualValueAndNotMet() {
        DomainEvent event = eventWithPayload(EventType.TWIN_CREATED, UUID.randomUUID(), "INFO", Map.of());
        Rule rule = ruleWithCondition(RuleField.SENSOR_TYPE, RuleOperator.EQ, "GAS_SENSOR");

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        ConditionEvidence evidence = result.evidence().get(0);
        assertNull(evidence.actualValue());
        assertFalse(evidence.conditionMet());
    }

    @Test
    void evidence_nonNumericValueForNumericOperator_retainsRawValueAndNotMet() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO",
                Map.of("value", "not-a-number"));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.GT, "30.0");

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        ConditionEvidence evidence = result.evidence().get(0);
        assertEquals("not-a-number", evidence.actualValue(), "the raw non-numeric value must be preserved, not discarded");
        assertFalse(evidence.conditionMet());
    }

    @Test
    void evidence_conditionOrdering_isPreservedAcrossThreeConditions() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "WARNING", Map.of("value", 35.0));
        Rule rule = ruleWithConditions(
                new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"),
                new RuleCondition(RuleField.SEVERITY, RuleOperator.NE, "CRITICAL"),
                new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30.0")
        );

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertTrue(result.matched());
        assertEquals(3, result.evidence().size());
        assertEquals(RuleField.EVENT_TYPE, result.evidence().get(0).field());
        assertEquals(RuleField.SEVERITY, result.evidence().get(1).field());
        assertEquals(RuleField.VALUE, result.evidence().get(2).field());
    }

    // ---------- Phase 13: capability-aware, multi-capability, twin-state evaluation ----------

    private DigitalTwin twin(UUID deviceId, String sensorType, double value, Instant timestamp) {
        return new DigitalTwin(deviceId, timestamp, sensorType, value, "UNIT", timestamp, Map.of());
    }

    private RuleCondition deviceCondition(UUID deviceId) {
        return new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, deviceId.toString(), null);
    }

    private RuleCondition capabilityCondition(RuleOperator operator, String expectedValue, CapabilityType type) {
        return new RuleCondition(RuleField.VALUE, operator, expectedValue, type);
    }

    @Test
    void capabilityAwareCondition_readsFromDigitalTwinRegistry_notFromEventTriggeringPayload() {
        UUID deviceId = UUID.randomUUID();
        Instant twinTimestamp = Instant.now().minusSeconds(30);
        when(digitalTwinRegistry.get(deviceId, "GAS")).thenReturn(Optional.of(twin(deviceId, "GAS", 850, twinTimestamp)));

        Rule rule = ruleWithConditions(deviceCondition(deviceId), capabilityCondition(RuleOperator.GT, "800", CapabilityType.GAS));
        // The triggering event is for a totally different capability (MOTION) -
        // the GAS condition must still be evaluated from twin state, never from
        // this event's own payload (which has no "GAS" value at all).
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, deviceId, "INFO", Map.of("sensorType", "MOTION", "value", 1.0));

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertTrue(result.matched());
        ConditionEvidence gasEvidence = result.evidence().get(1);
        assertEquals(850.0, gasEvidence.actualValue());
        assertEquals(twinTimestamp, gasEvidence.stateTimestamp(), "capability-aware evidence must record the twin's own timestamp");
    }

    @Test
    void multiCapabilityAnd_gasAboveThresholdAndMotionTrue_bothReadFromTwinState_matches() {
        UUID deviceId = UUID.randomUUID();
        when(digitalTwinRegistry.get(deviceId, "GAS")).thenReturn(Optional.of(twin(deviceId, "GAS", 900, Instant.now())));
        when(digitalTwinRegistry.get(deviceId, "MOTION")).thenReturn(Optional.of(twin(deviceId, "MOTION", 1.0, Instant.now().minusSeconds(120))));

        Rule rule = ruleWithConditions(
                deviceCondition(deviceId),
                capabilityCondition(RuleOperator.GT, "800", CapabilityType.GAS),
                capabilityCondition(RuleOperator.EQ, "true", CapabilityType.MOTION)
        );
        // Triggered by the GAS reading - MOTION's *prior* state must still be
        // enough to satisfy the MOTION condition.
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, deviceId, "INFO", Map.of("sensorType", "GAS", "value", 900.0));

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertTrue(result.matched(), "GAS>800 AND MOTION==true must match using current twin state for both capabilities");
    }

    @Test
    void multiDeviceIsolation_ruleScopedToD1_neverUsesD2sMotionState() {
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();

        // D1: GAS=900, MOTION=false. D2: GAS=100, MOTION=true.
        when(digitalTwinRegistry.get(d1, "GAS")).thenReturn(Optional.of(twin(d1, "GAS", 900, Instant.now())));
        when(digitalTwinRegistry.get(d1, "MOTION")).thenReturn(Optional.of(twin(d1, "MOTION", 0.0, Instant.now())));
        when(digitalTwinRegistry.get(d2, "GAS")).thenReturn(Optional.of(twin(d2, "GAS", 100, Instant.now())));
        when(digitalTwinRegistry.get(d2, "MOTION")).thenReturn(Optional.of(twin(d2, "MOTION", 1.0, Instant.now())));

        Rule ruleScopedToD1 = ruleWithConditions(
                deviceCondition(d1),
                capabilityCondition(RuleOperator.GT, "800", CapabilityType.GAS),
                capabilityCondition(RuleOperator.EQ, "true", CapabilityType.MOTION)
        );

        // D1's own GAS telemetry triggers evaluation.
        DomainEvent d1Event = eventWithPayload(EventType.TWIN_UPDATED, d1, "INFO", Map.of("sensorType", "GAS", "value", 900.0));

        RuleEvaluationResult result = evaluator.evaluate(ruleScopedToD1, d1Event);

        // D1's GAS condition is met (900 > 800) but D1's OWN motion is false -
        // the rule must NOT match by accidentally borrowing D2's MOTION=true.
        assertFalse(result.matched(), "a rule scoped to D1 must never read D2's MOTION state, even though D2's MOTION is true");
        assertEquals(false, result.evidence().get(2).actualValue(), "the MOTION evidence must reflect D1's own state (false), never D2's (true)");

        // digitalTwinRegistry.get(d2, ...) must never have been consulted at all
        // for this D1-scoped rule.
        org.mockito.Mockito.verify(digitalTwinRegistry, org.mockito.Mockito.never()).get(eq(d2), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void missingCapabilityState_recordsMissingEvidence_conditionNotMet() {
        UUID deviceId = UUID.randomUUID();
        when(digitalTwinRegistry.get(deviceId, "MOTION")).thenReturn(Optional.empty());

        Rule rule = ruleWithConditions(deviceCondition(deviceId), capabilityCondition(RuleOperator.EQ, "true", CapabilityType.MOTION));
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, deviceId, "INFO", Map.of("sensorType", "MOTION", "value", 1.0));

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertFalse(result.matched());
        assertEquals("MISSING", result.evidence().get(1).actualValue(), "a never-observed capability must be recorded explicitly as MISSING, never coerced to false/0");
        assertFalse(result.evidence().get(1).conditionMet());
    }

    @Test
    void presentButOldCapabilityState_stillCountsAsPresent_noTtlRejection() {
        UUID deviceId = UUID.randomUUID();
        Instant veryOld = Instant.now().minusSeconds(60 * 60 * 24 * 30); // 30 days old
        when(digitalTwinRegistry.get(deviceId, "TEMPERATURE")).thenReturn(Optional.of(twin(deviceId, "TEMPERATURE", 40, veryOld)));

        Rule rule = ruleWithConditions(deviceCondition(deviceId), capabilityCondition(RuleOperator.GT, "35", CapabilityType.TEMPERATURE));
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, deviceId, "INFO", Map.of("sensorType", "TEMPERATURE", "value", 40.0));

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertTrue(result.matched(), "Phase 13 does not enforce staleness - a present-but-old reading still counts");
        assertEquals(veryOld, result.evidence().get(1).stateTimestamp(), "the (old) timestamp must still be recorded for auditability");
    }

    @Test
    void booleanCapability_derivedFromNumericTwinValue_never_fromStringParsing() {
        UUID deviceId = UUID.randomUUID();
        // The Phase 12 simulator (and TelemetryRequest.value's Double contract)
        // encodes MOTION as 1.0/0.0, never the string "true"/"false".
        when(digitalTwinRegistry.get(deviceId, "MOTION")).thenReturn(Optional.of(twin(deviceId, "MOTION", 1.0, Instant.now())));

        Rule rule = ruleWithConditions(deviceCondition(deviceId), capabilityCondition(RuleOperator.EQ, "true", CapabilityType.MOTION));
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, deviceId, "INFO", Map.of("sensorType", "MOTION", "value", 1.0));

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertTrue(result.matched());
        assertEquals(Boolean.TRUE, result.evidence().get(1).actualValue(), "evidence must record a real boolean, derived from the 1.0/0.0 twin value");
    }

    @Test
    void implicitDevice_noDeviceIdCondition_usesTriggeringEventsOwnDevice() {
        UUID deviceId = UUID.randomUUID();
        when(digitalTwinRegistry.get(deviceId, "GAS")).thenReturn(Optional.of(twin(deviceId, "GAS", 900, Instant.now())));

        // Platform-wide rule: no DEVICE_ID condition at all.
        Rule rule = ruleWithConditions(capabilityCondition(RuleOperator.GT, "800", CapabilityType.GAS));
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, deviceId, "INFO", Map.of("sensorType", "GAS", "value", 900.0));

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertTrue(result.matched(), "a platform-wide capability-aware rule must resolve the device from the triggering event");
    }

    @Test
    void legacyCondition_capabilityTypeNull_neverConsultsDigitalTwinRegistry() {
        DomainEvent event = eventWithPayload(EventType.TWIN_UPDATED, UUID.randomUUID(), "INFO", Map.of("value", 35.0));
        Rule rule = ruleWithCondition(RuleField.VALUE, RuleOperator.GT, "30.0");

        RuleEvaluationResult result = evaluator.evaluate(rule, event);

        assertTrue(result.matched());
        assertNull(result.evidence().get(0).stateTimestamp(), "a legacy, event-payload-based condition must never populate stateTimestamp");
        org.mockito.Mockito.verifyNoInteractions(digitalTwinRegistry);
    }

    private static UUID eq(UUID value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
