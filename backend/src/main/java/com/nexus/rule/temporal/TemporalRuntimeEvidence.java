package com.nexus.rule.temporal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable copy of one temporal runtime row, as captured by a frozen snapshot or read for inspection. */
public record TemporalRuntimeEvidence(UUID ruleId, UUID ruleVersionId, long ruleRevision, String conditionId, UUID scopeDeviceId,
        long generation, String operator, String phase, Instant armedAt, Instant dueAt, Instant coverageEnd,
        List<Map<String, Object>> members, Map<String, Object> watermark, long sequence, String resetReason, Instant updatedAt) {
    public TemporalRuntimeEvidence {
        members = members == null ? List.of() : List.copyOf(members);
        watermark = watermark == null ? Map.of() : Map.copyOf(watermark);
    }
    public static String key(String conditionId, UUID scope) { return conditionId + "|" + scope; }
}
