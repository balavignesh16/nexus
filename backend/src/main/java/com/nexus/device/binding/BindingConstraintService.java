package com.nexus.device.binding;

import com.nexus.device.capability.definition.CapabilityDefinition;
import com.nexus.device.capability.definition.CapabilityValueType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/** Validates instance constraints against the definition and template ceiling. */
@Service
public class BindingConstraintService {
    public Map<String,Object> narrow(CapabilityDefinition definition, Map<String,Object> template,
            Map<String,Object> override) {
        Map<String,Object> base = template == null ? Map.of() : template;
        Map<String,Object> requested = override == null ? Map.of() : override;
        validateTemplateBase(definition, base);
        Map<String,Object> merged = new LinkedHashMap<>(base);
        for (var entry : requested.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            switch (key) {
                case "minimum", "maximum" -> {
                    if (definition.getValueType() != CapabilityValueType.NUMBER) throw new IllegalArgumentException("Numeric bounds require NUMBER capability");
                    BigDecimal candidate = decimal(value);
                    BigDecimal floor = base.containsKey("minimum") ? decimal(base.get("minimum")) : definition.getMinimum();
                    BigDecimal ceiling = base.containsKey("maximum") ? decimal(base.get("maximum")) : definition.getMaximum();
                    if (key.equals("minimum") && floor != null && candidate.compareTo(floor) < 0
                            || key.equals("maximum") && ceiling != null && candidate.compareTo(ceiling) > 0)
                        throw new IllegalArgumentException("Binding bounds may only narrow the allowed range");
                    merged.put(key, candidate);
                }
                case "channels", "enumValues" -> {
                    if (key.equals("channels") && !"relay".equals(definition.getKey()))
                        throw new IllegalArgumentException("Channels are supported only for relay bindings");
                    if (key.equals("enumValues") && definition.getValueType() != CapabilityValueType.ENUM)
                        throw new IllegalArgumentException("Enum values require ENUM capability");
                    if (!(value instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException(key + " must be a nonempty list");
                    List<?> allowed = key.equals("channels") ? (List<?>) base.get("channels") :
                            base.containsKey("enumValues") ? (List<?>) base.get("enumValues") : definition.getEnumValues();
                    if (allowed == null || !allowed.containsAll(list)) throw new IllegalArgumentException(key + " may only be narrowed");
                    merged.put(key, List.copyOf(list));
                }
                default -> throw new IllegalArgumentException("Unsupported binding override: " + key);
            }
        }
        BigDecimal min = merged.containsKey("minimum") ? decimal(merged.get("minimum")) : definition.getMinimum();
        BigDecimal max = merged.containsKey("maximum") ? decimal(merged.get("maximum")) : definition.getMaximum();
        if (min != null && max != null && min.compareTo(max) > 0) throw new IllegalArgumentException("Binding minimum exceeds maximum");
        return Map.copyOf(merged);
    }

    private void validateTemplateBase(CapabilityDefinition definition, Map<String,Object> base) {
        for (var entry : base.entrySet()) {
            switch (entry.getKey()) {
                case "minimum", "maximum" -> {
                    if (definition.getValueType() != CapabilityValueType.NUMBER)
                        throw new IllegalArgumentException("Numeric template bounds require NUMBER capability");
                    BigDecimal candidate = decimal(entry.getValue());
                    if (entry.getKey().equals("minimum") && definition.getMinimum() != null
                            && candidate.compareTo(definition.getMinimum()) < 0
                            || entry.getKey().equals("maximum") && definition.getMaximum() != null
                            && candidate.compareTo(definition.getMaximum()) > 0)
                        throw new IllegalArgumentException("Template bounds may only narrow the definition");
                }
                case "enumValues" -> {
                    if (definition.getValueType() != CapabilityValueType.ENUM
                            || !(entry.getValue() instanceof List<?> list) || list.isEmpty()
                            || !definition.getEnumValues().containsAll(list))
                        throw new IllegalArgumentException("Template enum values may only narrow the definition");
                }
                case "channels" -> {
                    if (!"relay".equals(definition.getKey()) || !(entry.getValue() instanceof List<?> list)
                            || list.isEmpty() || new HashSet<>(list).size() != list.size()
                            || list.stream().anyMatch(v -> !(v instanceof Number n) || n.intValue() < 1))
                        throw new IllegalArgumentException("Invalid template relay channels");
                }
                default -> throw new IllegalArgumentException("Unsupported template constraint: " + entry.getKey());
            }
        }
        BigDecimal min = base.containsKey("minimum") ? decimal(base.get("minimum")) : definition.getMinimum();
        BigDecimal max = base.containsKey("maximum") ? decimal(base.get("maximum")) : definition.getMaximum();
        if (min != null && max != null && min.compareTo(max) > 0)
            throw new IllegalArgumentException("Template minimum exceeds maximum");
    }

    public String unit(CapabilityDefinition definition, String unit) {
        String effective = unit == null ? definition.getUnit() : unit;
        if (effective != null && !definition.getAllowedUnits().isEmpty()
                && !definition.getAllowedUnits().contains(effective))
            throw new IllegalArgumentException("Unit is not allowed by capability definition");
        return effective;
    }

    public void validateValue(CapabilityDefinition definition, Map<String,Object> configuration, Object value) {
        Map<String,Object> c = configuration == null ? Map.of() : configuration;
        if (definition.getValueType() == CapabilityValueType.NUMBER && value instanceof Number number) {
            BigDecimal actual = decimal(number);
            if (c.containsKey("minimum") && actual.compareTo(decimal(c.get("minimum"))) < 0
                    || c.containsKey("maximum") && actual.compareTo(decimal(c.get("maximum"))) > 0)
                throw new IllegalArgumentException("Value is outside the device binding range");
        }
        if (definition.getValueType() == CapabilityValueType.ENUM && c.containsKey("enumValues")
                && !((List<?>) c.get("enumValues")).contains(value))
            throw new IllegalArgumentException("Value is not allowed by the device binding");
        if ("relay".equals(definition.getKey()) && c.containsKey("channels") && value instanceof Map<?,?> map
                && !((List<?>) c.get("channels")).contains(map.get("channel")))
            throw new IllegalArgumentException("Relay channel is not allowed by the device binding");
    }

    private BigDecimal decimal(Object value) {
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid numeric binding constraint", exception); }
    }
}
