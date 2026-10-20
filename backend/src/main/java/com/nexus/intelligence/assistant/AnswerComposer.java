package com.nexus.intelligence.assistant;

import com.nexus.intelligence.assistant.AssistantAnswer.Kind;
import com.nexus.intelligence.assistant.AssistantAnswer.Statement;
import com.nexus.intelligence.evidence.EvidenceItem;
import com.nexus.intelligence.retrieval.EvidenceResult;
import com.nexus.intelligence.retrieval.EvidenceStatus;
import com.nexus.intelligence.retrieval.Freshness;
import com.nexus.intelligence.trend.TrendResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes the answer sentences FROM STORED EVIDENCE with fixed templates. Every FACT names the evidence references that support it and every reference must resolve in
 * the evidence the lookups returned; a statement whose references do not resolve is dropped, never shown. A missing fact is stated as missing (and never as a physical
 * condition), and each lookup contributes the limits that say what its evidence cannot show. Nothing here calls a model or reads a table: it is a pure function of
 * {@link EvidenceResult}s, which is what makes the offline answer and the model-ordered answer contain exactly the same sentences.
 */
public final class AnswerComposer {
    private AnswerComposer() {}

    public record Composed(List<Statement> statements, List<EvidenceItem> sources, int dropped) {}

    public static Composed compose(List<EvidenceResult> results, TrendResult trend, String trendLabel) {
        List<Statement> out = new ArrayList<>();
        List<EvidenceItem> sources = new ArrayList<>();
        Set<String> sourceRefs = new HashSet<>();
        int[] dropped = new int[1];
        int[] next = new int[] {1};
        Set<String> limitTexts = new HashSet<>();
        for (EvidenceResult r : results) {
            for (EvidenceItem e : r.evidence()) if (sourceRefs.add(e.ref())) sources.add(e);
        }
        for (EvidenceResult r : results) {
            Set<String> resolvable = new HashSet<>();
            r.evidence().forEach(e -> resolvable.add(e.ref()));
            Adder add = new Adder(out, resolvable, next, dropped);
            describe(r, add);
            if (r.status() != EvidenceStatus.OBSERVED && r.status() != EvidenceStatus.DERIVED) add.notice(statusSentence(r));
            for (String limit : r.limits()) if (limitTexts.add(limit)) out.add(new Statement("s" + next[0]++, Kind.LIMIT, limit, List.of()));
        }
        if (trend != null) {
            sources.add(EvidenceItem.present("trend:" + trendLabel, "trend direction of " + trendLabel, trend.direction().name()));
            describeTrend(trend, trendLabel, out, next, limitTexts);
        }
        return new Composed(out, sources, dropped[0]);
    }

    private static final class Adder {
        private final List<Statement> out;
        private final Set<String> resolvable;
        private final int[] next;
        private final int[] dropped;

        Adder(List<Statement> out, Set<String> resolvable, int[] next, int[] dropped) {
            this.out = out;
            this.resolvable = resolvable;
            this.next = next;
            this.dropped = dropped;
        }

        void fact(String text, String... refs) {
            List<String> ok = new ArrayList<>();
            for (String ref : refs) if (ref != null && resolvable.contains(ref)) ok.add(ref);
            if (ok.isEmpty()) {
                dropped[0]++;
                return;
            }
            out.add(new Statement("s" + next[0]++, Kind.FACT, text, ok));
        }

        void notice(String text) {
            out.add(new Statement("s" + next[0]++, Kind.NOTICE, text, List.of()));
        }
    }

    private static String statusSentence(EvidenceResult r) {
        String what = r.entity() == null ? "the requested entity" : (r.entity().name() != null ? "\"" + r.entity().name() + "\"" : r.entity().type() + " " + r.entity().id());
        return switch (r.status()) {
            case NOT_FOUND -> "No stored record was found for " + what + ": " + clean(r.reason());
            case INSUFFICIENT_EVIDENCE -> "The platform holds too little to answer for " + what + ": " + clean(r.reason());
            case NOT_AVAILABLE -> "This could not be looked up (" + clean(r.reason()) + "), so nothing is concluded about " + what + ".";
            default -> clean(r.reason());
        };
    }

    private static String clean(String s) {
        if (s == null) return "no detail";
        int colon = s.indexOf(": ");
        String t = colon > 0 && colon < 30 && s.substring(0, colon).equals(s.substring(0, colon).toUpperCase()) ? s.substring(colon + 2) : s;
        return t.endsWith(".") ? t : t + ".";
    }

    private static String str(Object o) {
        if (o instanceof Double || o instanceof Float) return trim(((Number) o).doubleValue());
        return o == null ? "not recorded" : String.valueOf(o);
    }

    private static String quote(Object o) {
        return o == null ? "not recorded" : "\"" + o + "\"";
    }

    private static String age(Map<String, Object> row) {
        Object f = row.get("freshness");
        if (f == null) return "";
        if (Freshness.FUTURE_DATED.name().equals(f)) return " (the device stamped a time later than the backend allows, so its age is unknown)";
        return " (" + String.valueOf(f).toLowerCase() + ")";
    }

    @SuppressWarnings("unchecked")
    private static void describe(EvidenceResult r, Adder add) {
        if (r.status() != EvidenceStatus.OBSERVED && r.status() != EvidenceStatus.DERIVED) return;
        String id = r.entity() == null ? null : r.entity().id();
        String name = r.entity() == null || r.entity().name() == null ? "the device" : quote(r.entity().name());
        switch (r.lookup()) {
            case DEVICE_STATE -> {
                Map<String, Object> row = r.rows().get(0);
                add.fact(name + " is registered with status " + str(row.get("registrationStatus")) + " (type " + str(row.get("deviceType")) + ", mode " + str(row.get("mode")) + ").", "device:" + id + "/registrationStatus");
                if (row.get("connectivityStatus") != null)
                    add.fact("Its last recorded connectivity status is " + row.get("connectivityStatus") + ", changed at " + str(row.get("connectivityChangedAt")) + ".", "device:" + id + "/connectivityStatus");
                else add.fact("No connectivity status is recorded for it.", "device:" + id + "/connectivityStatus");
                List<Map<String, Object>> caps = (List<Map<String, Object>>) row.get("capabilities");
                if (caps != null && !caps.isEmpty()) {
                    List<String> parts = new ArrayList<>();
                    for (Map<String, Object> c : caps) parts.add(str(c.get("capabilityType")) + " (" + str(c.get("direction")) + ")");
                    add.fact("It declares " + caps.size() + " capabilities: " + String.join(", ", parts) + ".", "device:" + id + "/registrationStatus");
                }
            }
            case TWIN_STATE -> {
                for (Map<String, Object> row : r.rows()) {
                    boolean reported = "REPORTED".equals(row.get("section"));
                    String unit = row.get("unit") == null ? "" : " " + row.get("unit");
                    if (reported)
                        add.fact(name + " last reported " + str(row.get("stateKey")) + " = " + str(row.get("value")) + unit + " (device time " + str(row.get("deviceEventTime")) + ", received " + str(row.get("backendReceivedAt")) + ").",
                                "twin:" + id + "/reported/" + row.get("stateKey"));
                    else
                        add.fact("The desired state of " + str(row.get("stateKey")) + " is " + str(row.get("value")) + unit + " (source " + str(row.get("source")) + ", requested " + str(row.get("requestedAt")) + ").",
                                "twin:" + id + "/desired/" + row.get("stateKey"));
                }
            }
            case LATEST_TELEMETRY -> {
                for (Map<String, Object> row : r.rows()) {
                    String unit = row.get("unit") == null ? "" : " " + row.get("unit");
                    add.fact("The latest stored reading of " + str(row.get("capabilityKey")) + " on " + name + " is " + str(row.get("value")) + unit + ", stamped by the device at " + str(row.get("deviceEventTime")) + age(row) + ".",
                            "telemetry:" + row.get("readingId"));
                }
            }
            case TELEMETRY_HISTORY -> {
                List<Map<String, Object>> rows = r.rows();
                Map<String, Object> newest = rows.get(0), oldest = rows.get(rows.size() - 1);
                String unit = newest.get("unit") == null ? "" : " " + newest.get("unit");
                double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
                boolean numeric = true;
                for (Map<String, Object> row : rows) {
                    if (row.get("value") instanceof Number n) {
                        min = Math.min(min, n.doubleValue());
                        max = Math.max(max, n.doubleValue());
                    } else numeric = false;
                }
                add.fact(rows.size() + " stored readings of " + str(newest.get("capabilityKey")) + " on " + name + " cover " + str(oldest.get("deviceEventTime")) + " to " + str(newest.get("deviceEventTime")) + (r.truncated() ? " (more exist beyond this page)" : "") + ".",
                        "telemetry:" + oldest.get("readingId"), "telemetry:" + newest.get("readingId"));
                if (numeric) add.fact("In that window the stored values range from " + trim(min) + " to " + trim(max) + unit + "; the newest is " + str(newest.get("value")) + unit + ".", "telemetry:" + newest.get("readingId"));
            }
            case CONNECTIVITY -> {
                Map<String, Object> row = r.rows().get(0);
                add.fact("The last connectivity observation for " + name + " is " + str(row.get("status")) + ", recorded at " + str(row.get("lastChangedAt")) + ".", "connectivity:" + id + "/status");
            }
            case ACTIVE_RULES -> {
                int active = 0;
                for (Map<String, Object> row : r.rows()) {
                    boolean isActive = "ACTIVE".equals(row.get("section"));
                    if (isActive) active++;
                    if (r.rows().indexOf(row) >= 15) continue;
                    if (isActive)
                        add.fact("Rule " + quote(row.get("name")) + " (priority " + str(row.get("priority")) + ") is active at version " + str(row.get("activeVersionId")) + ".", "rule:" + row.get("ruleId") + "/activeVersion");
                    else add.fact("Rule " + quote(row.get("name")) + " is not active (lifecycle " + str(row.get("lifecycle")) + ").", "rule:" + row.get("ruleId") + "/lifecycle");
                }
                if (r.rows().size() > 15) add.notice("Only the first 15 of " + r.rows().size() + " rules are listed here.");
                if (active == 0) add.notice("No active rule was found in this result.");
            }
            case RULE_MATCHES -> {
                for (Map<String, Object> row : r.rows()) {
                    if ("NON_MATCH_DRY_RUN".equals(row.get("section"))) {
                        List<String> refs = new ArrayList<>();
                        for (EvidenceItem e : r.evidence()) if (!e.ref().startsWith("match:")) refs.add(e.ref());
                        String summary = "A dry-run evaluation of the stored rule and state (hypothetical, not a historical record) says: " + str(row.get("summary"));
                        add.fact(summary, refs.isEmpty() ? new String[0] : new String[] {refs.get(0)});
                        List<String> platform = (List<String>) row.get("platformStatements");
                        if (platform != null) for (String s : platform) add.fact(s, refs.isEmpty() ? new String[0] : new String[] {refs.get(0)});
                    } else {
                        add.fact("Rule " + row.get("ruleId") + " matched at " + str(row.get("matchedAt")) + " (trigger " + str(row.get("triggerType")) + ", version " + str(row.get("ruleVersionId")) + ").", "match:" + row.get("matchId") + "/matchedAt");
                    }
                }
            }
            case COMMAND_HISTORY -> {
                for (Map<String, Object> row : r.rows()) {
                    String c = "command:" + row.get("commandId") + "/status";
                    add.fact("Command " + row.get("commandId") + " (" + str(row.get("command")) + ") was accepted at " + str(row.get("acceptedAt")) + " with status " + str(row.get("status"))
                            + (row.get("outboxState") == null ? "" : "; its delivery state is " + row.get("outboxState") + " after " + str(row.get("attempts")) + " attempt(s)") + ".", c);
                }
            }
            case DELIVERY_EVIDENCE -> {
                for (Map<String, Object> row : r.rows()) {
                    String section = str(row.get("section"));
                    switch (section) {
                        case "ACCEPTANCE" -> add.fact("Acceptance: the command was accepted at " + str(row.get("acceptedAt")) + " with status " + str(row.get("status")) + ".", "command:" + id + "/status");
                        case "PUBLICATION" -> add.fact(row.get("publishedAt") == null ? "Publication: no publication time is recorded for this command." : "Publication: the command was published at " + row.get("publishedAt") + ".", "command:" + id + "/publishedAt");
                        case "OUTBOX" -> add.fact("Delivery queue: state " + str(row.get("state")) + " after " + str(row.get("attempts")) + " of " + str(row.get("maxAttempts")) + " attempts"
                                + (row.get("terminalReason") == null ? "" : ", ended with " + row.get("terminalReason")) + ".", "command:" + id + "/outbox/state");
                        case "ACK" -> add.fact(row.get("ackStatus") == null ? "Acknowledgement: none is recorded." : "Acknowledgement: " + row.get("ackStatus") + ", received at " + str(row.get("ackReceivedAt")) + ".", "command:" + id + "/ack/status");
                        case "TIMEOUT" -> add.fact("Timeout: the acknowledgement deadline is " + str(row.get("ackDeadlineAt")) + "; a timeout " + ("true".equals(row.get("timedOut")) ? "was" : "was not") + " recorded.", "command:" + id + "/ack/status");
                        case "CONVERGENCE" -> add.fact("Verification: " + str(row.get("verificationStatus")) + (row.get("comparisonResult") == null ? "" : " (comparison " + row.get("comparisonResult") + ")") + ".", "command:" + id + "/verification/status");
                        default -> {
                            if ("STATE_FACT".equals(section)) add.fact("History fact " + row.get("sequence") + ": " + str(row.get("factType")) + " (" + str(row.get("fromState")) + " to " + str(row.get("toState")) + ") at " + str(row.get("occurredAt")) + ".", "command:" + id + "/history/" + row.get("sequence"));
                        }
                    }
                }
            }
            case VERIFICATION -> {
                for (Map<String, Object> row : r.rows())
                    add.fact("Verification of " + str(row.get("targetKey")) + " on " + name + " is " + str(row.get("verificationStatus")) + (row.get("comparisonResult") == null ? "" : " (comparison " + row.get("comparisonResult") + ")")
                            + (row.get("discrepancyReason") == null ? "" : "; discrepancy: " + row.get("discrepancyReason")) + ".", "verification:" + id + "/" + row.get("targetKey") + "/status");
            }
            case ANOMALIES -> {
                for (Map<String, Object> row : r.rows()) {
                    List<String> known = (List<String>) row.get("known"), uncertain = (List<String>) row.get("uncertain");
                    add.fact("Observation (" + str(row.get("category")) + ", " + str(row.get("capabilityKey")) + "): " + str(row.get("status")) + ". " + str(row.get("reason"))
                            + (known == null || known.isEmpty() ? "" : " Established: " + String.join(" ", known)) + (uncertain == null || uncertain.isEmpty() ? "" : " Not established: " + String.join(" ", uncertain)), "finding:" + row.get("findingId"));
                }
            }
            case RECOMMENDATIONS -> {
                for (Map<String, Object> row : r.rows())
                    add.fact("Suggestion for a person to review (" + str(row.get("category")) + ", " + str(row.get("status")) + "): " + str(row.get("statement")) + " " + str(row.get("effect")), "recommendation:" + row.get("recommendationId"));
            }
        }
    }

    private static String trim(double d) {
        return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static void describeTrend(TrendResult t, String label, List<Statement> out, int[] next, Set<String> limitTexts) {
        String ref = "trend:" + label;
        String unit = t.unit() == null ? "" : " " + t.unit();
        StringBuilder s = new StringBuilder("Trend of " + label.substring(label.indexOf('/') + 1) + ": ");
        switch (t.direction()) {
            case INCREASING -> s.append("the readings are increasing (descriptive), from ").append(trim(t.firstValue())).append(" to ").append(trim(t.lastValue())).append(unit).append(" over ").append(t.sampleCount()).append(" readings");
            case DECREASING -> s.append("the readings are decreasing (descriptive), from ").append(trim(t.firstValue())).append(" to ").append(trim(t.lastValue())).append(unit).append(" over ").append(t.sampleCount()).append(" readings");
            case STABLE -> s.append("the readings are stable (no change beyond the sensor resolution and the noise) over ").append(t.sampleCount()).append(" readings");
            case INSUFFICIENT_EVIDENCE -> s.append("not enough evidence: ").append(t.reason());
            case NOT_APPLICABLE -> s.append("not applicable: ").append(t.reason());
        }
        if (t.rate() != null && (t.direction() == com.nexus.intelligence.trend.TrendDirection.INCREASING || t.direction() == com.nexus.intelligence.trend.TrendDirection.DECREASING))
            s.append("; fitted rate ").append(trim(Math.round(t.rate().perMinute() * 10_000) / 10_000.0)).append(" ").append(t.rate().unit());
        s.append(". ");
        if (t.established() != null && t.established().established()) s.append("The direction is also statistically established (regular readings, significant test).");
        else if (t.direction() == com.nexus.intelligence.trend.TrendDirection.INCREASING || t.direction() == com.nexus.intelligence.trend.TrendDirection.DECREASING)
            s.append("It is not statistically established (").append(String.join(", ", t.established().reasons())).append("), so no trend strength is claimed.");
        out.add(new Statement("s" + next[0]++, Kind.FACT, s.toString().strip(), List.of(ref)));
        for (String n : t.notes()) if (limitTexts.add(n)) out.add(new Statement("s" + next[0]++, Kind.LIMIT, n, List.of()));
        for (String l : t.limits()) if (limitTexts.add(l)) out.add(new Statement("s" + next[0]++, Kind.LIMIT, l, List.of()));
    }
}
