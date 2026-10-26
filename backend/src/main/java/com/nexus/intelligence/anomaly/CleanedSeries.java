package com.nexus.intelligence.anomaly;

import java.util.List;
import java.util.Map;

/**
 * A series ready for analysis, or the reason it is not analysable. Quality counts say exactly what was dropped so a reader can see the evidence was not
 * silently reshaped: duplicates collapsed (same time and same value), conflicting duplicates removed (same time, different values: no basis to pick one),
 * future-dated rows (event time beyond the backend instant plus the skew tolerance) and non-finite values.
 */
public record CleanedSeries(Applicability applicability, List<Sample> samples, String unit, Quality quality) {
    public enum Applicability { NUMERIC, EMPTY, NOT_NUMERIC, MIXED_VALUE_TYPES, MIXED_UNITS }

    public record Quality(int rawRows, int kept, int duplicatesCollapsed, int conflictingTimestamps, int futureDated, int nonFinite, boolean truncated) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("rawRows", rawRows);
            m.put("kept", kept);
            m.put("duplicatesCollapsed", duplicatesCollapsed);
            m.put("conflictingTimestampsRemoved", conflictingTimestamps);
            m.put("futureDatedExcluded", futureDated);
            m.put("nonFiniteExcluded", nonFinite);
            m.put("readLimitReached", truncated);
            return m;
        }
    }
}
