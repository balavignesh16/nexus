package com.nexus.rule.application;

import com.nexus.event.domain.DomainEvent;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleEvaluationResult;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RuleEvaluator {

    public RuleEvaluationResult evaluate(Rule rule, DomainEvent event) {
        if (rule.conditions() == null || rule.conditions().isEmpty()) {
            return new RuleEvaluationResult(false, rule, event, "No conditions defined");
        }

        for (RuleCondition condition : rule.conditions()) {
            boolean conditionMet = evaluateCondition(condition, event);
            if (!conditionMet) {
                return new RuleEvaluationResult(false, rule, event, "Condition not met: " + condition.field());
            }
        }
        
        return new RuleEvaluationResult(true, rule, event, "All conditions met");
    }

    private boolean evaluateCondition(RuleCondition condition, DomainEvent event) {
        Object actualValue = extractActualValue(condition, event);
        if (actualValue == null) return false;

        String expectedStr = condition.expectedValue();
        String actualStr = actualValue.toString();

        switch (condition.operator()) {
            case EQ:
                return actualStr.equals(expectedStr);
            case NE:
                return !actualStr.equals(expectedStr);
            case GT:
            case LT:
            case GTE:
            case LTE:
                return evaluateNumeric(actualStr, expectedStr, condition.operator());
            default:
                return false;
        }
    }

    private Object extractActualValue(RuleCondition condition, DomainEvent event) {
        return switch (condition.field()) {
            case EVENT_TYPE -> event.eventType().name();
            case DEVICE_ID -> event.deviceId().toString();
            case SEVERITY -> event.severity();
            case SENSOR_TYPE -> {
                Map<String, Object> payload = event.payload();
                yield payload != null ? payload.get("sensorType") : null;
            }
            case VALUE -> {
                Map<String, Object> payload = event.payload();
                // Check currentValue (for SENSOR_VALUE_CHANGED) or value (for TWIN_UPDATED)
                if (payload == null) yield null;
                if (payload.containsKey("currentValue")) yield payload.get("currentValue");
                yield payload.get("value");
            }
        };
    }

    private boolean evaluateNumeric(String actualStr, String expectedStr, com.nexus.rule.domain.RuleOperator op) {
        try {
            double actual = Double.parseDouble(actualStr);
            double expected = Double.parseDouble(expectedStr);
            return switch (op) {
                case GT -> actual > expected;
                case LT -> actual < expected;
                case GTE -> actual >= expected;
                case LTE -> actual <= expected;
                default -> false;
            };
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
