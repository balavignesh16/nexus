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
import com.nexus.rule.snapshot.*;
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
    private RuleCapabilityValidator references;
    @Autowired void setReferences(RuleCapabilityValidator references){this.references=references;}

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

    /**
     * Compatibility adapter: reads live registry/contract/cache state. Production routing uses the
     * frozen-context overload; this exists for direct fixtures and must not be called by RuleMatcher.
     */
    public RuleEvaluationResult evaluate(Rule rule, DomainEvent event) {
        return evaluate(rule, event, event==null?null:event.deviceId(), new LiveSource());
    }

    /** Evaluates exclusively against inputs captured by one REPEATABLE READ snapshot. */
    public RuleEvaluationResult evaluate(Rule rule, DomainEvent event, RuleEvaluationContext context) {
        var result = evaluate(rule, event, context.trigger().deviceId(), new FrozenSource(context));
        return new RuleEvaluationResult(result.matched(), result.rule(), result.event(), result.message(), result.evidence(), context);
    }

    private interface Source {
        boolean hasAuthority();
        CapturedCapability capability(UUID device, String identifier);
        List<DigitalTwin> twins(UUID device);
        Optional<DigitalTwin> byStateKey(UUID device, String stateKey);
        Optional<DigitalTwin> byWire(UUID device, String sensorType);
        TwinStateQualityEvidence quality(DigitalTwin twin);
        /** Evidence for a temporal condition; the live adapter has no durable runtime and never satisfies one. */
        ConditionEvidence temporal(Rule rule, RuleCondition condition, UUID scope);
    }

    private final class LiveSource implements Source {
        public boolean hasAuthority() { return references != null; }
        public CapturedCapability capability(UUID device, String identifier) {
            return CapturedCapability.of(references.resolveInstalled(device, identifier, com.nexus.device.capability.CapabilityDirection.READ));
        }
        public List<DigitalTwin> twins(UUID device) { return digitalTwinRegistry.getAllForDevice(device); }
        public Optional<DigitalTwin> byStateKey(UUID device, String key) { return digitalTwinRegistry.getByStateKey(device, key); }
        public Optional<DigitalTwin> byWire(UUID device, String type) { return digitalTwinRegistry.get(device, type); }
        public TwinStateQualityEvidence quality(DigitalTwin twin) { return qualityEvaluator == null ? null : qualityEvaluator.evaluate(twin); }
        public ConditionEvidence temporal(Rule rule, RuleCondition condition, UUID scope) {
            return new ConditionEvidence(condition.field(), condition.operator(), condition.expectedValue(), "TEMPORAL_RUNTIME_UNAVAILABLE", false);
        }
    }

    private final class FrozenSource implements Source {
        private final RuleEvaluationContext ctx;
        FrozenSource(RuleEvaluationContext ctx) { this.ctx = ctx; }
        public boolean hasAuthority() { return true; }
        public CapturedCapability capability(UUID device, String identifier) {
            String key = RuleEvaluationSnapshot.capabilityKey(device, identifier, com.nexus.device.capability.CapabilityDirection.READ.name());
            CapturedCapability found = ctx.snapshot().capabilities().get(key);
            if (found == null) throw new IllegalStateException("Capability was not resolvable in the frozen snapshot");
            return found;
        }
        public List<DigitalTwin> twins(UUID device) { return ctx.snapshot().twins().getOrDefault(device, List.of()); }
        public Optional<DigitalTwin> byStateKey(UUID device, String key) { return twins(device).stream().filter(t -> key.equals(t.stateKey())).findFirst(); }
        public Optional<DigitalTwin> byWire(UUID device, String type) {
            return twins(device).stream().filter(t -> type.equals(t.latestSensorType())
                || type.equals(t.lastWireIdentifier()) || type.equals(t.capabilityKey())).findFirst();
        }
        public TwinStateQualityEvidence quality(DigitalTwin twin) {
            if (qualityEvaluator == null) return null;
            var inputs = ctx.snapshot().qualityInputs().get(RuleEvaluationSnapshot.twinKey(twin));
            if (inputs == null) throw new IllegalStateException("Twin was not captured with quality inputs");
            return qualityEvaluator.evaluate(twin, ctx.evaluationTime(), inputs);
        }
        public ConditionEvidence temporal(Rule rule, RuleCondition condition, UUID scope) {
            var identity = rule.executionIdentity();
            var state = identity == null || scope == null ? null
                : ctx.snapshot().temporal().get(RuleEvaluationSnapshot.temporalKey(identity.ruleVersionId(), condition.conditionId(), scope));
            var attributes = ctx.trigger().attributes();
            boolean addressed = ctx.trigger().type() == com.nexus.rule.domain.TriggerType.TEMPORAL_DUE
                && String.valueOf(condition.conditionId()).equals(String.valueOf(attributes.get("conditionId")))
                && state != null && attributes.get("sequence") instanceof Number sequence && sequence.longValue() == state.sequence();
            boolean met = addressed && "SATISFIED".equals(state.phase()) && state.generation() == identity.executionEpoch();
            var actual = new java.util.LinkedHashMap<String, Object>();
            actual.put("operator", condition.temporal().operator().name());
            if (state == null) actual.put("runtime", "ABSENT");
            else {
                actual.put("phase", state.phase()); actual.put("sequence", state.sequence()); actual.put("generation", state.generation());
                actual.put("armedAt", String.valueOf(state.armedAt())); actual.put("dueAt", String.valueOf(state.dueAt()));
                actual.put("members", state.members().size()); actual.put("resetReason", state.resetReason());
                if (!addressed) actual.put("stale", "TRIGGER_DOES_NOT_ADDRESS_CURRENT_OCCURRENCE");
            }
            return new ConditionEvidence(condition.field(), condition.operator(), condition.expectedValue(), FrozenValues.freeze(actual), met);
        }
    }

    private RuleEvaluationResult evaluate(Rule rule, DomainEvent event, UUID triggerDeviceId, Source src) {
        if (rule.conditions() == null || rule.conditions().isEmpty()) {
            return new RuleEvaluationResult(false, rule, event, "No conditions defined");
        }

        UUID resolvedDeviceId = resolveDeviceId(rule, triggerDeviceId);

        List<ConditionEvidence> evidence = new ArrayList<>();
        for (RuleCondition condition : rule.conditions()) {
            ConditionEvidence conditionEvidence = evaluateCondition(rule, condition, event, triggerDeviceId, resolvedDeviceId, src);
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
    private UUID resolveDeviceId(Rule rule, UUID triggerDeviceId) {
        for (RuleCondition c : rule.conditions()) {
            if (c.field() == RuleField.DEVICE_ID && c.expectedValue() != null) {
                try {
                    return UUID.fromString(c.expectedValue());
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return triggerDeviceId;
    }

    private ConditionEvidence evaluateCondition(Rule rule, RuleCondition condition, DomainEvent event, UUID triggerDeviceId, UUID resolvedDeviceId, Source src) {
        if (condition.temporal() != null) return src.temporal(rule, condition, resolvedDeviceId);
        return evaluateUnderlying(condition, event, triggerDeviceId, resolvedDeviceId, src);
    }

    /**
     * One captured qualified observation for a temporal condition's capability, evaluated against the frozen context only.
     * The evidence carries value, receipt time, quality, unit and the eligibility that already includes R1/U1 unit safety.
     */
    public ConditionEvidence observe(RuleCondition condition, UUID scope, RuleEvaluationContext context) {
        return evaluateUnderlying(condition, null, context.trigger().deviceId(), scope, new FrozenSource(context));
    }

    /**
     * Evaluates one condition against an observation exactly as it was recorded by its source transaction (its own typed value,
     * unit, receipt time and quality inputs), never against the current Twin. Contracts still come from the frozen context.
     */
    public ConditionEvidence observeRecorded(RuleCondition condition, UUID scope, RuleEvaluationContext context,
            DigitalTwin recorded, TwinStateQualityEvaluator.Inputs inputs) {
        var frozen = new FrozenSource(context);
        Source source = new Source() {
            public boolean hasAuthority() { return true; }
            public CapturedCapability capability(UUID device, String identifier) { return frozen.capability(device, identifier); }
            public List<DigitalTwin> twins(UUID device) { return device.equals(recorded.deviceId()) ? List.of(recorded) : List.of(); }
            public Optional<DigitalTwin> byStateKey(UUID device, String key) { return twins(device).stream().filter(t -> key.equals(t.stateKey())).findFirst(); }
            public Optional<DigitalTwin> byWire(UUID device, String type) {
                return twins(device).stream().filter(t -> type.equals(t.latestSensorType()) || type.equals(t.lastWireIdentifier()) || type.equals(t.capabilityKey())).findFirst();
            }
            public TwinStateQualityEvidence quality(DigitalTwin twin) {
                return qualityEvaluator == null ? null : qualityEvaluator.evaluate(twin, twin.receivedAt(), inputs);   // quality AT RECEIPT
            }
            public ConditionEvidence temporal(Rule rule, RuleCondition c, UUID s) { return frozen.temporal(rule, c, s); }
        };
        return evaluateUnderlying(condition, null, context.trigger().deviceId(), scope, source);
    }

    private ConditionEvidence evaluateUnderlying(RuleCondition condition, DomainEvent event, UUID triggerDeviceId, UUID resolvedDeviceId, Source src) {
        // DEVICE_ID always compares the canonical source scope, never a capability value.
        if(condition.field()==RuleField.DEVICE_ID){
            Object actual=extractActualValue(condition,event);boolean met=false;
            if(triggerDeviceId!=null)try{boolean same=triggerDeviceId.equals(UUID.fromString(condition.expectedValue()));met=condition.operator()==com.nexus.rule.domain.RuleOperator.EQ?same:condition.operator()==com.nexus.rule.domain.RuleOperator.NE&&!same;}catch(IllegalArgumentException invalid){/* Invalid scope fails closed. */}
            return new ConditionEvidence(condition.field(),condition.operator(),condition.expectedValue(),actual,met);
        }
        if (condition.capabilityKey() != null) return evaluateGenericCapabilityCondition(condition,resolvedDeviceId,src);
        if (condition.capabilityType() != null) {
            return evaluateCapabilityCondition(condition, resolvedDeviceId, src);
        }
        Object actualValue = extractActualValue(condition, event);
        boolean conditionMet = evaluateConditionMet(condition, actualValue);
        return new ConditionEvidence(condition.field(), condition.operator(), condition.expectedValue(), actualValue, conditionMet);
    }

    private ConditionEvidence evaluateGenericCapabilityCondition(RuleCondition condition,UUID deviceId,Source src){
        if(deviceId==null)return evidence(condition,MISSING,false,null,null);
        CapturedCapability resolved=null;
        if(src.hasAuthority()){
            try{resolved=src.capability(deviceId,condition.capabilityKey());}
            catch(RuntimeException invalid){return evidence(condition,MISSING,false,null,null);}
        }
        final var reference=resolved;
        var candidates=src.twins(deviceId).stream().filter(t->reference==null?
            condition.capabilityKey().equalsIgnoreCase(t.capabilityKey()==null?"":t.capabilityKey())||condition.capabilityKey().equalsIgnoreCase(t.latestSensorType()):
            reference.bindingId()!=null?reference.bindingId().equals(t.bindingId()):reference.definitionKey().equals(t.capabilityKey())).toList();
        Optional<DigitalTwin> found=candidates.size()==1?Optional.of(candidates.getFirst()):Optional.empty();
        if(found.isEmpty()||found.get().typedValue()==null)return evidence(condition,MISSING,false,found.orElse(null),null);
        DigitalTwin twin=found.get(); TwinStateQualityEvidence quality=src.quality(twin);
        boolean compatible=hasCompatibleUnits(condition,twin,resolved);
        boolean eligibleGeneric=compatible&&(quality==null||quality.state()==TwinStateQuality.GOOD);
        Object actual=FrozenValues.freeze(twin.typedValue()); boolean met=eligibleGeneric
                &&evaluateGeneric(actual,condition.expectedValue(),condition.operator(),twin.valueType());
        return evidence(condition,actual,met,twin,quality).withEligibility(eligibleGeneric);
    }

    private boolean evaluateGeneric(Object actual,String expected,com.nexus.rule.domain.RuleOperator op,com.nexus.device.capability.definition.CapabilityValueType type){
        if(type==null||actual==null)return false;
        if(type==com.nexus.device.capability.definition.CapabilityValueType.NUMBER){try{var a=new java.math.BigDecimal(actual.toString());var e=new java.math.BigDecimal(expected);int c=a.compareTo(e);return switch(op){case EQ->c==0;case NE->c!=0;case GT->c>0;case GTE->c>=0;case LT->c<0;case LTE->c<=0;};}catch(Exception x){return false;}}
        if(type==com.nexus.device.capability.definition.CapabilityValueType.BOOLEAN){if(!"true".equalsIgnoreCase(expected)&&!"false".equalsIgnoreCase(expected))return false;boolean eq=actual.equals(Boolean.valueOf(expected));return op==com.nexus.rule.domain.RuleOperator.EQ?eq:op==com.nexus.rule.domain.RuleOperator.NE&&!eq;}
        if(type==com.nexus.device.capability.definition.CapabilityValueType.STRING||type==com.nexus.device.capability.definition.CapabilityValueType.ENUM){boolean eq=actual.equals(expected);return op==com.nexus.rule.domain.RuleOperator.EQ?eq:op==com.nexus.rule.domain.RuleOperator.NE&&!eq;}
        return false;
    }

    /** Legacy syntax retains its comparisons, not historical wire-name ownership or inferred units. */
    private ConditionEvidence evaluateCapabilityCondition(RuleCondition condition, UUID resolvedDeviceId, Source src) {
        if (resolvedDeviceId == null) {
            return evidence(condition,MISSING,false,null,null);
        }
        CapturedCapability resolved=null;
        Optional<DigitalTwin> twin;
        if(src.hasAuthority()){
            try{
                resolved=src.capability(resolvedDeviceId,
                    com.nexus.device.capability.definition.LegacyCapabilityDefinitionMapping.keyFor(condition.capabilityType()));
                var reference=resolved;
                String stateKey=reference.bindingId()!=null?"binding:"+reference.bindingId():"legacy:"+condition.capabilityType().name();
                twin=src.byStateKey(resolvedDeviceId,stateKey).filter(t->
                    resolvedDeviceId.equals(t.deviceId()) && stateKey.equals(t.stateKey())
                    && (reference.bindingId()!=null?
                        reference.bindingId().equals(t.bindingId()) && reference.definitionId().equals(t.capabilityDefinitionId()):
                        t.bindingId()==null && condition.capabilityType().name().equals(t.legacyCapabilityType())
                            && (t.capabilityDefinitionId()==null || reference.definitionId().equals(t.capabilityDefinitionId())))
                    && (t.capabilityKey()==null || reference.definitionKey().equals(t.capabilityKey())));
            }catch(RuntimeException invalid){return evidence(condition,MISSING,false,null,null);}
        }else{
            // Existing manually constructed legacy unit fixtures have no installed authority.
            // Spring's mandatory setReferences injection always selects the canonical branch above.
            twin=src.byWire(resolvedDeviceId,condition.capabilityType().name());
        }
        if(twin.isEmpty() || twin.get().typedValue()==null)return evidence(condition,MISSING,false,twin.orElse(null),null);
        DigitalTwin currentTwin = twin.get();
        TwinStateQualityEvidence quality=src.quality(currentTwin);
        boolean compatible=hasCompatibleUnits(condition,currentTwin,resolved);
        boolean eligible=compatible&&(quality==null||quality.state()==TwinStateQuality.GOOD);
        CapabilityValueKind kind = CapabilityValueKind.of(condition.capabilityType());
        Object actual=currentTwin.typedValue();
        boolean met=false;
        if(actual instanceof Number number){
            double raw=number.doubleValue();
            actual=kind==CapabilityValueKind.BOOLEAN?(raw!=0.0):raw;
            met=eligible&&Double.isFinite(raw)&&evaluateTypedConditionMet(condition,raw,kind);
        }else if(kind==CapabilityValueKind.BOOLEAN && actual instanceof Boolean bool){
            Boolean expected=parseBooleanOrNull(condition.expectedValue());
            met=eligible&&expected!=null && switch(condition.operator()){
                case EQ->bool.equals(expected);case NE->!bool.equals(expected);default->false;
            };
        }
        return evidence(condition,actual,met,currentTwin,quality).withEligibility(eligible);
    }

    private boolean hasCompatibleUnits(RuleCondition condition,DigitalTwin twin,CapturedCapability resolved){
        String effectiveUnit=resolved==null?twin.latestUnit():resolved.effectiveUnit();
        // NULL equality is safe only for an explicitly unitless installed contract,
        // not an unresolved choice of dimensional units. Preserve manual fixtures without references.
        boolean unitEstablished=resolved==null || effectiveUnit!=null || resolved.unitlessContract();
        return unitEstablished&&java.util.Objects.equals(twin.latestUnit(),effectiveUnit)
            && (condition.unit()==null || java.util.Objects.equals(condition.unit(),effectiveUnit));
    }

    private ConditionEvidence evidence(RuleCondition condition,Object actual,boolean met,DigitalTwin twin,TwinStateQualityEvidence q){
        boolean legacyCapability=condition.capabilityKey()==null && condition.capabilityType()!=null;
        String key=condition.capabilityKey()!=null?condition.capabilityKey():legacyCapability&&twin!=null?twin.capabilityKey():null;
        return new ConditionEvidence(condition.field(),condition.operator(),condition.expectedValue(),actual,met,
                twin==null?null:twin.latestTimestamp(),key,twin==null?null:twin.capabilityDefinitionId(),
                twin==null?null:twin.bindingId(),twin==null||twin.valueType()==null?null:twin.valueType().name(),
                legacyCapability&&twin!=null?twin.latestUnit():condition.unit()!=null?condition.unit():twin==null?null:twin.latestUnit(),q==null?null:q.state().name(),
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
        // Typed triggers carry no DomainEvent: payload predicates have nothing to read and fail closed.
        if (event == null) return null;
        return switch (condition.field()) {
            case EVENT_TYPE -> event.eventType().name();
            case DEVICE_ID -> event.deviceId()==null?null:event.deviceId().toString();
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
