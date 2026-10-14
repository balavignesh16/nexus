package com.nexus.rule.temporal;

import com.nexus.device.binding.runtime.CapabilityResolutionSource;
import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.rule.trigger.TriggerObservation;
import com.nexus.twin.application.TwinStateQualityEvaluator;
import com.nexus.twin.domain.DigitalTwin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * The authoritative sample an ACCEPTED_TELEMETRY fact recorded in its own source transaction: identity, typed value, effective
 * unit, receipt time and the quality inputs (connectivity, freshness threshold, persisted-value validity) that applied at
 * receipt. Temporal evidence is built from this record, never from whatever the Twin row says later. A fact without a complete
 * record is not reconstructable and callers must fail closed.
 */
public record RecordedObservation(DigitalTwin twin, TwinStateQualityEvaluator.Inputs inputs) {

    /** Serializable form written by RuleSourceFacts. NUMBER values are strings so BigDecimal precision survives JSON. */
    public static Map<String, Object> describe(DigitalTwin twin, TwinStateQualityEvaluator.Inputs inputs) {
        var sample = new LinkedHashMap<String, Object>();
        sample.put("latestTimestamp", String.valueOf(twin.latestTimestamp()));
        sample.put("latestSensorType", twin.latestSensorType());
        sample.put("wireIdentifier", twin.lastWireIdentifier());
        sample.put("valueType", twin.valueType() == null ? null : twin.valueType().name());
        Object value = twin.typedValue();
        sample.put("value", value instanceof Number n ? n.toString() : value);
        sample.put("unit", twin.latestUnit());
        sample.put("lastSeen", String.valueOf(twin.lastSeen()));
        sample.put("receivedAt", String.valueOf(twin.receivedAt()));
        sample.put("definitionId", twin.capabilityDefinitionId() == null ? null : twin.capabilityDefinitionId().toString());
        sample.put("bindingId", twin.bindingId() == null ? null : twin.bindingId().toString());
        sample.put("capabilityKey", twin.capabilityKey());
        sample.put("resolutionSource", twin.resolutionSource() == null ? null : twin.resolutionSource().name());
        sample.put("connectivity", inputs.connectivity());
        sample.put("thresholdSeconds", inputs.thresholdSeconds());
        sample.put("valueValid", inputs.persistedValueValid());
        return sample;
    }

    @SuppressWarnings("unchecked")
    public static Optional<RecordedObservation> from(TriggerObservation fact) {
        try {
            Object raw = fact.sourceRef().get("sample");
            if (!(raw instanceof Map<?, ?> sample) || fact.deviceId() == null) return Optional.empty();
            var s = (Map<String, Object>) sample;
            Object valueType = s.get("valueType");
            if (valueType == null || s.get("value") == null || s.get("receivedAt") == null || "null".equals(String.valueOf(s.get("receivedAt")))) return Optional.empty();
            CapabilityValueType type = CapabilityValueType.valueOf(valueType.toString());
            Object value = switch (type) {
                case NUMBER -> new BigDecimal(s.get("value").toString());
                case BOOLEAN -> Boolean.valueOf(s.get("value").toString());
                case STRING, ENUM -> s.get("value").toString();
                case OBJECT -> (Map<String, Object>) s.get("value");
            };
            Instant received = Instant.parse(s.get("receivedAt").toString());
            var twin = new DigitalTwin(fact.deviceId(), Instant.parse(s.get("latestTimestamp").toString()), String.valueOf(s.get("latestSensorType")),
                s.get("wireIdentifier") == null ? null : s.get("wireIdentifier").toString(), value, (String) s.get("unit"),
                Instant.parse(s.get("lastSeen").toString()), received, new LinkedHashMap<>(),
                uuid(s.get("definitionId")), uuid(s.get("bindingId")), (String) s.get("capabilityKey"), type,
                s.get("resolutionSource") == null ? null : CapabilityResolutionSource.valueOf(s.get("resolutionSource").toString()));
            var inputs = new TwinStateQualityEvaluator.Inputs(String.valueOf(s.get("connectivity")), ((Number) s.get("thresholdSeconds")).intValue(),
                Boolean.TRUE.equals(s.get("valueValid")));
            return Optional.of(new RecordedObservation(twin, inputs));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static UUID uuid(Object value) { return value == null ? null : UUID.fromString(value.toString()); }
}
