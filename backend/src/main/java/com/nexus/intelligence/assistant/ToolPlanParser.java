package com.nexus.intelligence.assistant;

import com.nexus.intelligence.retrieval.LookupRequest;
import com.nexus.intelligence.retrieval.LookupType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates a model's lookup plan against the fixed catalogue. The model can only choose among the twelve lookup types and fill parameters whose values must
 * come from the question itself (a device name or an id the model invented is rejected), so a plan can never reach beyond what the question names and never name
 * a write operation. A refusal is accepted only as WRITE_REQUEST or OUT_OF_SCOPE and then carries no lookups.
 */
public final class ToolPlanParser {
    private ToolPlanParser() {}

    public enum Code { UNKNOWN_LOOKUP_TYPE, UNKNOWN_FIELD, WRONG_TYPE, TOO_MANY_LOOKUPS, DEVICE_NAME_NOT_IN_QUESTION, ID_NOT_IN_QUESTION, BAD_IDENTIFIER, BAD_CAPABILITY, OUT_OF_RANGE, BAD_REFUSAL, REFUSAL_WITH_LOOKUPS }

    public record Plan(List<LookupRequest> requests, String refusal) {
        public Plan {
            requests = List.copyOf(requests);
        }
    }

    public sealed interface Result permits Accepted, Rejected {}

    public record Accepted(Plan plan) implements Result {}

    public record Rejected(Code code) implements Result {}

    private static final Set<String> FIELDS = Set.of("type", "deviceId", "deviceName", "ruleId", "commandId", "capabilityKey", "lookbackHours", "limit");
    private static final Pattern CAPABILITY = Pattern.compile("[a-z][a-z0-9_]{0,99}");

    @SuppressWarnings("unchecked")
    public static Result validate(Map<String, Object> values, String question) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        Object refusalValue = values.get("refusal");
        String refusal = null;
        if (refusalValue != null) {
            if (!(refusalValue instanceof String r) || !(r.equals("WRITE_REQUEST") || r.equals("OUT_OF_SCOPE"))) return new Rejected(Code.BAD_REFUSAL);
            refusal = (String) refusalValue;
        }
        Object lookups = values.get("lookups");
        if (!(lookups instanceof List<?> list)) return new Rejected(Code.WRONG_TYPE);
        if (refusal != null && !list.isEmpty()) return new Rejected(Code.REFUSAL_WITH_LOOKUPS);
        if (list.size() > AssistantContracts.MAX_LOOKUPS) return new Rejected(Code.TOO_MANY_LOOKUPS);
        Set<String> seen = new LinkedHashSet<>();
        List<LookupRequest> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) return new Rejected(Code.WRONG_TYPE);
            Map<String, Object> m = (Map<String, Object>) raw;
            for (String key : m.keySet()) if (!FIELDS.contains(key)) return new Rejected(Code.UNKNOWN_FIELD);
            if (!(m.get("type") instanceof String typeName)) return new Rejected(Code.WRONG_TYPE);
            LookupType type;
            try {
                type = LookupType.valueOf(typeName);
            } catch (IllegalArgumentException unknown) {
                return new Rejected(Code.UNKNOWN_LOOKUP_TYPE);
            }
            LookupRequest r = LookupRequest.of(type);
            if (m.containsKey("deviceName")) {
                if (!(m.get("deviceName") instanceof String name) || name.isBlank() || name.length() > 100) return new Rejected(Code.WRONG_TYPE);
                if (!text.contains(name.toLowerCase(Locale.ROOT))) return new Rejected(Code.DEVICE_NAME_NOT_IN_QUESTION);
                r = r.withDeviceName(name);
            }
            for (String idField : List.of("deviceId", "ruleId", "commandId")) {
                if (!m.containsKey(idField)) continue;
                if (!(m.get(idField) instanceof String id)) return new Rejected(Code.WRONG_TYPE);
                UUID uuid;
                try {
                    uuid = UUID.fromString(id);
                } catch (IllegalArgumentException invalid) {
                    return new Rejected(Code.BAD_IDENTIFIER);
                }
                if (!text.contains(id.toLowerCase(Locale.ROOT))) return new Rejected(Code.ID_NOT_IN_QUESTION);
                r = switch (idField) {
                    case "deviceId" -> r.withDevice(uuid);
                    case "ruleId" -> r.withRule(uuid);
                    default -> r.withCommand(uuid);
                };
            }
            if (m.containsKey("capabilityKey")) {
                if (!(m.get("capabilityKey") instanceof String key)) return new Rejected(Code.WRONG_TYPE);
                if (!CAPABILITY.matcher(key).matches()) return new Rejected(Code.BAD_CAPABILITY);
                r = r.withCapability(key);
            }
            if (m.containsKey("lookbackHours")) {
                Integer hours = integer(m.get("lookbackHours"));
                if (hours == null) return new Rejected(Code.WRONG_TYPE);
                if (hours < 1 || hours > 720) return new Rejected(Code.OUT_OF_RANGE);
                r = r.withLookbackHours(hours);
            }
            if (m.containsKey("limit")) {
                Integer limit = integer(m.get("limit"));
                if (limit == null) return new Rejected(Code.WRONG_TYPE);
                if (limit < 1 || limit > 200) return new Rejected(Code.OUT_OF_RANGE);
                r = r.withLimit(limit);
            }
            String key = r.type() + "|" + r.deviceId() + "|" + r.deviceName() + "|" + r.ruleId() + "|" + r.commandId() + "|" + r.capabilityKey() + "|" + r.lookbackHours() + "|" + r.limit();
            if (seen.add(key)) out.add(r);
        }
        return new Accepted(new Plan(out, refusal));
    }

    private static Integer integer(Object o) {
        if (o instanceof Integer i) return i;
        if (o instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return l.intValue();
        if (o instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e9) return d.intValue();
        return null;
    }
}
