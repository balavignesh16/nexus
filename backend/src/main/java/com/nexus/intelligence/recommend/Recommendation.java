package com.nexus.intelligence.recommend;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A suggestion for a PERSON to look at something, derived only from persisted facts. It changes nothing and has no way to: it carries no command, no rule
 * edit and no approval. There is deliberately no confidence or probability field: the evidence is stated as counts, windows and the thresholds applied.
 * INSUFFICIENT_EVIDENCE means the facts were looked at and are too few to recommend anything.
 */
public record Recommendation(String id, Category category, Status status, Subject subject, String title, String statement, Map<String, Object> support,
                             Map<String, Object> parameters, Window window, List<String> evidenceRefs, String effect, Instant evaluatedAt) {
    public static final String NO_EFFECT = "None. This is a suggestion for a person to review; nothing was changed, sent or scheduled.";

    public enum Category {
        INSPECT_SENSOR, CALIBRATION_CHECK, INVESTIGATE_READINGS, INVESTIGATE_REPORTING_GAP, REVIEW_RULE_THRESHOLD, INVESTIGATE_FAILING_DEVICE, REVIEW_RULE_CONFLICT, MAINTENANCE_CHECK
    }

    public enum Status { RECOMMENDED, INSUFFICIENT_EVIDENCE }

    public record Subject(String deviceId, String ruleId, String capabilityKey) {}

    public record Window(Instant from, Instant to) {}
}
