package com.nexus.intelligence.retrieval;

import com.nexus.intelligence.evidence.EvidenceItem;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The answer to one lookup: what was asked, which entity it resolved to, the persisted facts (each row with an allowlisted set of fields and a resolvable evidence
 * reference), how old the newest evidence is, whether the read is complete, and in plain words what can NOT be concluded from it. A missing fact is never turned
 * into a physical condition: {@code limits} always says so.
 */
public record EvidenceResult(LookupType lookup, EvidenceStatus status, Entity entity, Instant evaluatedAt, Freshness freshness, Instant newestEvidenceAt, List<EvidenceItem> evidence,
                             List<Map<String, Object>> rows, boolean complete, boolean truncated, String nextCursor, List<String> notices, List<String> limits, String reason) {
    public record Entity(String type, String id, String name) {}

    public EvidenceResult {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(freshness, "freshness");
        evidence = List.copyOf(evidence);
        rows = List.copyOf(rows);
        notices = List.copyOf(notices);
        limits = List.copyOf(limits);
    }

    public static EvidenceResult unavailable(LookupType lookup, EvidenceStatus status, Entity entity, Instant at, String reason, List<String> limits) {
        return new EvidenceResult(lookup, status, entity, at, Freshness.UNKNOWN, null, List.of(), List.of(), false, false, null, List.of(), limits, reason);
    }
}
