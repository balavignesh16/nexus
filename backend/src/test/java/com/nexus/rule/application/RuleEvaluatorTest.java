package com.nexus.rule.application;

import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleOperator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for RuleEvaluator: every RuleOperator (EQ, NE, GT, LT, GTE,
 * LTE), the RuleField -> DomainEvent extraction logic, and the documented edge
 * cases (missing conditions, null/missing values, non-numeric comparisons,
 * multi-condition AND semantics). No mocking required - RuleEvaluator has no
 * dependencies.
 */
class RuleEvaluatorTest {

    private final RuleEvaluator evaluator = new RuleEvaluator();

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
}
