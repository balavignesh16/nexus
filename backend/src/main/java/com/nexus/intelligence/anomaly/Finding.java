package com.nexus.intelligence.anomaly;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One deterministic verdict about one capability of one device and one anomaly category. It is an OBSERVATION about persisted readings, never a diagnosis:
 * {@code confirmedFault} is always false, no probability or confidence is reported, and {@code known} / {@code uncertain} say in plain words what the
 * evidence does and does not establish. INSUFFICIENT_EVIDENCE names why (sample count, signal quality) and claims nothing about the device.
 */
public record Finding(String findingId, UUID deviceId, String capabilityKey, Category category, Status status, String reason, Window window, Map<String, Object> quality,
                      Map<String, Object> samples, Map<String, Object> statistics, Map<String, Object> rule, List<Map<String, Object>> measurements,
                      List<String> evidenceRefs, List<String> known, List<String> uncertain, boolean confirmedFault, Instant evaluatedAt) {
    public enum Category { FLATLINE, OUTLIER, SPIKE, GAP }

    public enum Status { OBSERVED, NOT_OBSERVED, INSUFFICIENT_EVIDENCE, NOT_APPLICABLE }

    /** Half-open intervals on the device's event-time axis: baseline [from, split), recent [split, to). */
    public record Window(Instant from, Instant split, Instant to) {}
}
