package com.nexus.rule.application;

import com.nexus.event.domain.DomainEvent;
import com.nexus.rule.domain.CapabilityValueKind;
import com.nexus.rule.domain.ConditionEvidence;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.rule.domain.RuleField;
import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.DigitalTwinRegistry;
import com.nexus.twin.application.TwinStateQualityEvaluator;
import com.nexus.twin.domain.TwinStateQualityEvidence;
import com.nexus.twin.domain.TwinStateQuality;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 13: a condition with {@code capabilityType == null} is evaluated
 * exactly as before Phase 13 - reading the triggering event's own payload,
 * byte-for-byte unchanged (see extractActualValue/evaluateConditionMet). A
 * condition with {@code capabilityType != null} is evaluated instead
 * against CURRENT DigitalTwinRegistry state for one device's one capability
 * - this is what makes multi-capability AND rules (e.g. "GAS > 800 AND
 * MOTION == true") correct: a GAS-triggered event can still see MOTION's
 * last-known state, because that state is read from the twin registry, not
 * from this event's own payload. DigitalTwinRegistry.get(deviceId,
 * sensorType) is a pure in-memory lookup (Phase 11 infrastructure) - this
 * adds zero per-event database queries.
 */
@Component
public class RuleEvaluator {

    private static final String MISSING = "MISSING";

    private final DigitalTwinRegistry digitalTwinRegistry;
    private final TwinStateQualityEvaluator qualityEvaluator;

    public RuleEvaluator(DigitalTwinRegistry digitalTwinRegistry) {
        this(digitalTwinRegistry,null);
    }

    @Autowired
    public RuleEvaluator(DigitalTwinRegistry digitalTwinRegistry,TwinStateQualityEvaluator qualityEvaluator) {
        this.digitalTwinRegistry = digitalTwinRegistry;
        this.qualityEvaluator=qualityEvaluator;
    }

    public RuleEvaluationResult evaluate(Rule rule, DomainEvent event) {
        if (rule.conditions() == null || rule.conditions().isEmpty()) {
            return new RuleEvaluationResult(false, rule, event, "No conditions defined");
        }

        UUID resolvedDeviceId = resolveDeviceId(rule, event);

        List<ConditionEvidence> evidence = new ArrayList<>();
        for (RuleCondition condition : rule.conditions()) {
            ConditionEvidence conditionEvidence = evaluateCondition(condition, event, resolvedDeviceId);
            evidence.add(conditionEvidence);
            if (!conditionEvidence.conditionMet()) {
                return new RuleEvaluationResult(false, rule, event, "Condition not met: " + condition.field(), evidence);
            }
        }

        return new RuleEvaluationResult(true, rule, event, "All conditions met", evidence);
    }

    /**
     * The single device every capability-typed condition in this rule is
     * scoped to (Phase 13 guardrail: strict single-device evaluation - never
     * a mix of devices within one rule's condition set). An explicit
     * DEVICE_ID condition pins it; otherwise the triggering event's own
     * device is used (a platform-wide rule still only ever evaluates one
     * device's state per evaluation instance, never two).
     */
    private UUID resolveDeviceId(Rule rule, DomainEvent event) {
        for (RuleCondition c : rule.conditions()) {
            if (c.field() == RuleField.DEVICE_ID && c.expectedValue() != null) {
                try {
                    return UUID.fromString(c.expectedValue());
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return event.deviceId();
    }

    private ConditionEvidence evaluateCondition(RuleCondition condition, DomainEvent event, UUID resolvedDeviceId) {
        if (condition.capabilityKey() != null) return evaluateGenericCapabilityCondition(condition,resolvedDeviceId);
        if (condition.capabilityType() != null) {
            return evaluateCapabilityCondition(condition, resolvedDeviceId);
        }
        Object actualValue = extractActualValue(condition, event);
        boolean conditionMet = evaluateConditionMet(condition, actualValue);
        return new ConditionEvidence(condition.field(), condition.operator(), condition.expectedValue(), actualValue, conditionMet);
    }

    private ConditionEvidence evaluateGenericCapabilityCondition(RuleCondition condition,UUID deviceId){
        if(deviceId==null)return evidence(condition,MISSING,false,null,null);
        Optional<DigitalTwin> found=digitalTwinRegistry.getAllForDevice(deviceId).stream().filter(t->condition.capabilityKey().equals(t.capabilityKey())||condition.capabilityKey().equalsIgnoreCase(t.latestSensorType())).findFirst();
        if(found.isEmpty()||found.get().typedValue()==null)return evidence(condition,MISSING,false,found.orElse(null),null);
        DigitalTwin twin=found.get(); TwinStateQualityEvidence quality=qualityEvaluator==null?null:qualityEvaluator.evaluate(twin);
        Object actual=twin.typedValue(); boolean met=(quality==null||quality.state()==TwinStateQuality.GOOD)
                &&evaluateGeneric(actual,condition.expectedValue(),condition.operator(),twin.valueType());
        return evidence(condition,actual,met,twin,quality);
    }

    private boolean evaluateGeneric(Object actual,String expected,com.nexus.rule.domain.RuleOperator op,com.nexus.device.capability.definition.CapabilityValueType type){
        if(type==null||actual==null)return false;
        if(type==com.nexus.device.capability.definition.CapabilityValueType.NUMBER){try{var a=new java.math.BigDecimal(actual.toString());var e=new java.math.BigDecimal(expected);int c=a.compareTo(e);return switch(op){case EQ->c==0;case NE->c!=0;case GT->c>0;case GTE->c>=0;case LT->c<0;case LTE->c<=0;};}catch(Exception x){return false;}}
        if(type==com.nexus.device.capability.definition.CapabilityValueType.BOOLEAN){if(!"true".equalsIgnoreCase(expected)&&!"false".equalsIgnoreCase(expected))return false;boolean eq=actual.equals(Boolean.valueOf(expected));return op==com.nexus.rule.domain.RuleOperator.EQ?eq:op==com.nexus.rule.domain.RuleOperator.NE&&!eq;}
        if(type==com.nexus.device.capability.definition.CapabilityValueType.STRING||type==com.nexus.device.capability.definition.CapabilityValueType.ENUM){boolean eq=actual.equals(expected);return op==com.nexus.rule.domain.RuleOperator.EQ?eq:op==com.nexus.rule.domain.RuleOperator.NE&&!eq;}
        return false;
    }

    /**
     * Reads current state for (resolvedDeviceId, condition.capabilityType)
     * from DigitalTwinRegistry - never from the triggering event's payload.
     * A device the rule couldn't resolve, or a capability the device has
     * never reported, is MISSING: evaluates to not-met, recorded explicitly
     * as "MISSING" evidence, never silently coerced to a false/zero value.
     * A present-but-old twin row still counts (no TTL/staleness rejection in
     * Phase 13) - its timestamp is recorded in evidence regardless.
     */
    private ConditionEvidence evaluateCapabilityCondition(RuleCondition condition, UUID resolvedDeviceId) {
        if (resolvedDeviceId == null) {
            return new ConditionEvidence(condition.field(), condition.operator(), condition.expectedValue(), MISSING, false, null);
        }

        Optional<DigitalTwin> twin = digitalTwinRegistry.get(resolvedDeviceId, condition.capabilityType().name());
        if (twin.isEmpty() || twin.get().latestValue() == null) {
            Instant twinTimestamp = twin.map(DigitalTwin::latestTimestamp).orElse(null);
            return new ConditionEvidence(condition.field(), condition.operator(), condition.expectedValue(), MISSING, false, twinTimestamp);
        }

        DigitalTwin currentTwin = twin.get();
        TwinStateQualityEvidence quality=qualityEvaluator==null?null:qualityEvaluator.evaluate(currentTwin);
        double rawValue = currentTwin.latestValue();
        CapabilityValueKind kind = CapabilityValueKind.of(condition.capabilityType());
        boolean conditionMet = (quality==null||quality.state()==TwinStateQuality.GOOD)&&evaluateTypedConditionMet(condition, rawValue, kind);
        Object actualValueForEvidence = kind == CapabilityValueKind.BOOLEAN ? (rawValue != 0.0) : rawValue;

        return evidence(condition,actualValueForEvidence,conditionMet,currentTwin,quality);
    }

    private ConditionEvidence evidence(RuleCondition condition,Object actual,boolean met,DigitalTwin twin,TwinStateQualityEvidence q){
        return new ConditionEvidence(condition.field(),condition.operator(),condition.expectedValue(),actual,met,
                twin==null?null:twin.latestTimestamp(),condition.capabilityKey(),twin==null?null:twin.capabilityDefinitionId(),
                twin==null?null:twin.bindingId(),twin==null||twin.valueType()==null?null:twin.valueType().name(),
                condition.unit()!=null?condition.unit():twin==null?null:twin.latestUnit(),q==null?null:q.state().name(),
                q==null?null:q.reason(),q==null?null:q.evaluatedAt(),q==null?null:q.receivedAt(),
                q==null?null:q.thresholdSeconds(),q==null?null:q.connectivity());
    }

    /**
     * BOOLEAN capabilities (MOTION, BUTTON) are never published as the
     * string "true"/"false" - the Phase 12 simulator (and, by the same
     * TelemetryRequest.value:Double contract, any real ESP32 firmware)
     * encodes them as 1.0/0.0 because the telemetry value field is a hard
     * Double. Deriving the boolean here from the twin's numeric value
     * (!= 0.0) - never Boolean.parseBoolean(actualValue) - is what makes
     * this correct against the real wire contract.
     */
    private boolean evaluateTypedConditionMet(RuleCondition condition, double rawValue, CapabilityValueKind kind) {
        if (kind == CapabilityValueKind.BOOLEAN) {
            boolean actual = rawValue != 0.0;
            Boolean expected = parseBooleanOrNull(condition.expectedValue());
            if (expected == null) return false;
            return switch (condition.operator()) {
                case EQ -> actual == expected;
                case NE -> actual != expected;
                default -> false; // GT/LT/GTE/LTE on a boolean capability is rejected at registration (RuleRegistry)
            };
        }

        try {
            double expected = Double.parseDouble(condition.expectedValue());
            return switch (condition.operator()) {
                case EQ -> rawValue == expected;
                case NE -> rawValue != expected;
                case GT -> rawValue > expected;
                case LT -> rawValue < expected;
                case GTE -> rawValue >= expected;
                case LTE -> rawValue <= expected;
            };
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Boolean parseBooleanOrNull(String value) {
        if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
        return null;
    }

    // ---- Legacy path (capabilityType == null) - unchanged from pre-Phase-13 ----

    private boolean evaluateConditionMet(RuleCondition condition, Object actualValue) {
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
