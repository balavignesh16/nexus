package com.nexus.intelligence.recommend;

import com.nexus.device.capability.definition.LegacyCapabilityDefinitionMapping;
import com.nexus.intelligence.anomaly.AnomalyReport;
import com.nexus.intelligence.anomaly.AnomalyService;
import com.nexus.intelligence.anomaly.CleanedSeries;
import com.nexus.intelligence.anomaly.Finding;
import com.nexus.intelligence.anomaly.RobustStats;
import com.nexus.intelligence.anomaly.Sample;
import com.nexus.intelligence.anomaly.SeriesCleaner;
import com.nexus.intelligence.anomaly.TelemetrySeriesReader;
import com.nexus.intelligence.recommend.Recommendation.Category;
import com.nexus.intelligence.recommend.Recommendation.Status;
import com.nexus.intelligence.recommend.Recommendation.Subject;
import com.nexus.intelligence.recommend.Recommendation.Window;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleOperator;
import com.nexus.rule.persistence.RuleVersionStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic, read-only recommendations built ONLY from persisted facts: anomaly findings over telemetry, Phase 4 command failures and current
 * verification state, Phase 3 arbitration suppressions, rule thresholds against recorded readings, and the current connectivity fact. The whole request runs
 * in a read-only transaction with a statement timeout, so a recommendation cannot write a row, and no collaborator here can publish, edit a rule, approve a
 * draft or touch a Twin (the architecture test enforces the imports). Identical evidence yields identical recommendations with identical ids; nothing is
 * stored, so there is nothing to duplicate or to expire.
 */
@Service
public class RecommendationService {
    /** {@code factWindow} is on the backend clock; {@code commandFactWindow} is the same span on the DATABASE clock, which stamps command history (Phase 4 R37). */
    public record Report(Instant evaluatedAt, boolean scopeFound, Window factWindow, Window commandFactWindow, Window telemetryWindow, List<Recommendation> recommendations, boolean truncated, List<String> notices) {}

    private final AnomalyService anomalies;
    private final TelemetrySeriesReader reader;
    private final FailureFactsReader facts;
    private final RuleVersionStore rules;
    private final RecommendationSettings settings;
    private final Clock clock;

    public RecommendationService(AnomalyService anomalies, TelemetrySeriesReader reader, FailureFactsReader facts, RuleVersionStore rules, RecommendationSettings settings, Clock clock) {
        this.anomalies = anomalies;
        this.reader = reader;
        this.facts = facts;
        this.rules = rules;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Report recommend(Optional<UUID> device, Optional<UUID> rule, Optional<Integer> factHours, Optional<Integer> telemetryHours) {
        Instant now = clock.instant();
        int fh = factHours.orElse(settings.factHours()), th = telemetryHours.orElse(anomalies.settings().windowHours());
        if (fh < 1 || fh > 720 || th < 1 || th > 720) throw new IllegalArgumentException("windows are 1 to 720 hours");
        Instant factFrom = now.minus(Duration.ofHours(fh)), telFrom = now.minus(Duration.ofHours(th));
        reader.boundStatements(8_000);
        List<String> notices = new ArrayList<>();
        notices.add("Connectivity history is not persisted, so offline frequency cannot be computed; only the current connectivity fact is used.");
        List<Recommendation> out = new ArrayList<>();
        List<UUID> devices;
        if (device.isPresent()) {
            if (!reader.deviceExists(device.get())) return new Report(now, false, null, null, null, List.of(), false, List.of("DEVICE_NOT_FOUND"));
            devices = List.of(device.get());
        } else if (rule.isPresent()) {
            devices = List.of();
        } else {
            devices = reader.devices(settings.maxDevices());
        }
        boolean truncated = device.isEmpty() && rule.isEmpty() && devices.size() >= settings.maxDevices();

        Instant dbNow = facts.databaseNow();
        Instant dbFrom = dbNow.minus(Duration.ofHours(fh));
        List<FailureFactsReader.DeviceFailures> failures = new ArrayList<>(facts.failures(device.orElse(null), factFrom, now, dbFrom, dbNow));
        failures.sort(Comparator.comparingInt(FailureFactsReader.DeviceFailures::total).reversed().thenComparing(f -> f.deviceId().toString()));
        Map<UUID, Integer> rank = new LinkedHashMap<>();
        for (FailureFactsReader.DeviceFailures f : failures) rank.put(f.deviceId(), rank.size() + 1);
        List<RuleVersionStore.View> active = device.isPresent() || rule.isEmpty() ? rules.list(true) : List.of();

        for (UUID d : devices) {
            AnomalyReport report = anomalies.analyse(d, Optional.empty(), Optional.of(th), Optional.empty());
            if (report.capabilityListTruncated()) notices.add("Device " + d + ": more capabilities reported than the per-device limit; only the first were analysed.");
            for (Finding f : report.findings()) if (f.status() == Finding.Status.OBSERVED) out.add(fromFinding(f, now));
            out.addAll(thresholds(d, active, telFrom, now));
            FailureFactsReader.DeviceFailures own = failures.stream().filter(f -> f.deviceId().equals(d)).findFirst().orElse(null);
            if (own != null) out.add(failing(own, rank.get(d), dbFrom, dbNow, now));
            Recommendation maintenance = maintenance(d, report, own, now, factFrom);
            if (maintenance != null) out.add(maintenance);
        }
        List<UUID> ruleScope = rule.isPresent() ? List.of(rule.get()) : device.isPresent() ? List.of() : facts.activeRules(settings.maxRules());
        for (UUID r : ruleScope) for (FailureFactsReader.Conflict c : facts.conflicts(r, factFrom, now)) out.add(conflict(c, factFrom, now));

        out.sort(Comparator.comparing((Recommendation x) -> x.category().ordinal()).thenComparing(x -> String.valueOf(x.subject().deviceId())).thenComparing(x -> String.valueOf(x.subject().ruleId()))
                .thenComparing(x -> String.valueOf(x.subject().capabilityKey())).thenComparing(Recommendation::id));
        if (out.size() > settings.perScopeCap()) {
            out = new ArrayList<>(out.subList(0, settings.perScopeCap()));
            truncated = true;
        }
        if (truncated) notices.add("The result was cut at the per-scope limit; narrow the scope to see the rest.");
        return new Report(now, true, new Window(factFrom, now), new Window(dbFrom, dbNow), new Window(telFrom, now), out, truncated, notices);
    }

    // ---------------------------------------------------------------- from anomaly findings

    static Recommendation fromFinding(Finding f, Instant now) {
        Category category = switch (f.category()) {
            case FLATLINE -> Category.INSPECT_SENSOR;
            case GAP -> Category.INVESTIGATE_REPORTING_GAP;
            case SPIKE -> Category.INVESTIGATE_READINGS;
            case OUTLIER -> consistentSide(f) ? Category.CALIBRATION_CHECK : Category.INVESTIGATE_READINGS;
        };
        String cap = f.capabilityKey();
        String title = switch (category) {
            case INSPECT_SENSOR -> "Check whether the " + cap + " reading on this device is stuck";
            case CALIBRATION_CHECK -> "Consider a calibration check of the " + cap + " reading";
            case INVESTIGATE_REPORTING_GAP -> "Look into why the " + cap + " readings stopped or paused";
            default -> "Look at the unusual " + cap + " readings";
        };
        String statement = String.join(" ", f.known()) + " What is not known: " + f.uncertain().getFirst();
        Map<String, Object> support = new LinkedHashMap<>(f.samples());
        support.put("flaggedMeasurements", f.measurements().size());
        support.put("findingId", f.findingId());
        Map<String, Object> parameters = new LinkedHashMap<>(f.rule());
        parameters.put("statistics", f.statistics());
        return new Recommendation(sha("rec", category, f.findingId()), category, Status.RECOMMENDED, new Subject(f.deviceId().toString(), null, cap), title, statement, support, parameters,
                new Window(f.window().from(), f.window().to()), f.evidenceRefs(), Recommendation.NO_EFFECT, now);
    }

    /** Three or more flagged readings, all on the same side of the baseline median: a level offset is one possible explanation, not a finding of one. */
    private static boolean consistentSide(Finding f) {
        if (f.category() != Finding.Category.OUTLIER || f.measurements().size() < 3) return false;
        Object median = f.statistics().get("baselineMedian");
        if (!(median instanceof Number m)) return false;
        boolean above = false, below = false;
        for (Map<String, Object> x : f.measurements()) {
            double v = ((Number) x.get("value")).doubleValue();
            if (v > m.doubleValue()) above = true;
            else if (v < m.doubleValue()) below = true;
        }
        return above != below;
    }

    // ---------------------------------------------------------------- thresholds (P6-061)

    private List<Recommendation> thresholds(UUID device, List<RuleVersionStore.View> active, Instant from, Instant now) {
        List<Recommendation> out = new ArrayList<>();
        for (RuleVersionStore.View view : active) {
            Rule rule = view.definition();
            if (rule.conditions() == null || rule.conditions().stream().noneMatch(c -> c.field() == RuleField.DEVICE_ID && c.operator() == RuleOperator.EQ && device.toString().equalsIgnoreCase(c.expectedValue()))) continue;
            for (RuleCondition c : rule.conditions()) {
                if (c.field() != RuleField.VALUE || c.operator() == RuleOperator.EQ || c.operator() == RuleOperator.NE) continue;
                String key = c.capabilityKey() != null ? c.capabilityKey() : c.capabilityType() != null ? LegacyCapabilityDefinitionMapping.keyFor(c.capabilityType()) : null;
                double threshold;
                try {
                    threshold = Double.parseDouble(c.expectedValue());
                } catch (RuntimeException notNumeric) {
                    continue;
                }
                if (key == null) continue;
                TelemetrySeriesReader.Read read = reader.read(device, key, from, now, anomalies.settings().maxSamples());
                CleanedSeries series = SeriesCleaner.clean(read.rows(), now, anomalies.settings().skewToleranceSeconds(), read.truncated());
                if (series.applicability() != CleanedSeries.Applicability.NUMERIC) continue;
                out.add(threshold(device, rule, c, key, threshold, series, from, now));
            }
        }
        return out;
    }

    private Recommendation threshold(UUID device, Rule rule, RuleCondition c, String key, double threshold, CleanedSeries series, Instant from, Instant now) {
        List<Sample> samples = series.samples();
        List<Double> values = samples.stream().map(Sample::value).toList();
        boolean upper = c.operator() == RuleOperator.GT || c.operator() == RuleOperator.GTE;
        long exceeding = values.stream().filter(v -> switch (c.operator()) {
            case GT -> v > threshold;
            case GTE -> v >= threshold;
            case LT -> v < threshold;
            default -> v <= threshold;
        }).count();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("operator", c.operator().name());
        parameters.put("currentThreshold", threshold);
        parameters.put("minThresholdSamples", settings.minThresholdSamples());
        parameters.put("alwaysTrueFraction", settings.neverFiredOrAlwaysTrueFraction());
        parameters.put("referencePercentile", upper ? 99 : 1);
        Map<String, Object> support = new LinkedHashMap<>();
        support.put("samples", values.size());
        support.put("conditionTrueFor", exceeding);
        List<String> refs = List.of("telemetry:" + device + "/" + key + "?from=" + from + "&to=" + now, "rule:" + rule.ruleId());
        Subject subject = new Subject(device.toString(), rule.ruleId().toString(), key);
        Window window = new Window(from, now);
        if (values.size() < settings.minThresholdSamples()) {
            return new Recommendation(sha("rec", Category.REVIEW_RULE_THRESHOLD, device, rule.ruleId(), key, c.operator(), threshold, values.size()), Category.REVIEW_RULE_THRESHOLD, Status.INSUFFICIENT_EVIDENCE, subject,
                    "Rule threshold for " + key + " cannot be assessed yet", "Only " + values.size() + " readings are persisted in the window; at least " + settings.minThresholdSamples() + " are needed before a percentile or an exceedance count says anything.",
                    support, parameters, window, refs, Recommendation.NO_EFFECT, now);
        }
        double fraction = exceeding / (double) values.size();
        double reference = RobustStats.percentile(values, upper ? 99 : 1);
        TelemetrySeriesReader.Range range = reader.range(key).orElse(null);
        double suggested = reference;
        boolean clamped = false;
        if (range != null) {
            if (range.maximum() != null && suggested > range.maximum()) { suggested = range.maximum(); clamped = true; }
            if (range.minimum() != null && suggested < range.minimum()) { suggested = range.minimum(); clamped = true; }
        }
        support.put("fractionTrue", Math.round(fraction * 1000.0) / 1000.0);
        support.put("observedMin", values.stream().mapToDouble(Double::doubleValue).min().orElse(0));
        support.put("observedMax", values.stream().mapToDouble(Double::doubleValue).max().orElse(0));
        support.put("referenceValue", suggested);
        support.put("referenceClampedToCapabilityRange", clamped);
        boolean never = exceeding == 0, always = fraction >= settings.neverFiredOrAlwaysTrueFraction();
        if (!never && !always) {
            return new Recommendation(sha("rec", Category.REVIEW_RULE_THRESHOLD, device, rule.ruleId(), key, c.operator(), threshold, values.size(), exceeding), Category.REVIEW_RULE_THRESHOLD, Status.INSUFFICIENT_EVIDENCE, subject,
                    "Rule threshold for " + key + " looks consistent with the readings", "The condition was true for " + exceeding + " of " + values.size() + " readings; nothing suggests a review.", support, parameters, window, refs,
                    Recommendation.NO_EFFECT, now);
        }
        String statement = "Rule \"" + rule.name() + "\" tests " + key + " " + c.operator() + " " + threshold + ". In the window the device persisted " + values.size() + " readings (min " + support.get("observedMin") + ", max "
                + support.get("observedMax") + "); the condition was true for " + exceeding + " of them. For reference, the " + (upper ? "99th" : "1st") + " percentile of the same readings is " + reference
                + (clamped ? " (kept within the capability's declared range as " + suggested + ")" : "") + ". Review whether the threshold fits what this device actually reports.";
        return new Recommendation(sha("rec", Category.REVIEW_RULE_THRESHOLD, device, rule.ruleId(), key, c.operator(), threshold, values.size(), exceeding), Category.REVIEW_RULE_THRESHOLD, Status.RECOMMENDED, subject,
                never ? "A rule threshold for " + key + " was never reached in the recorded readings" : "A rule condition on " + key + " was true for most recorded readings", statement, support, parameters, window, refs, Recommendation.NO_EFFECT, now);
    }

    // ---------------------------------------------------------------- failures, conflicts, maintenance

    private Recommendation failing(FailureFactsReader.DeviceFailures f, int rank, Instant dbFrom, Instant dbNow, Instant now) {
        Map<String, Object> support = new LinkedHashMap<>();
        support.put("deadLettered", f.deadLettered());
        support.put("ackTimedOut", f.ackTimedOut());
        support.put("deliveryUnknown", f.deliveryUnknown());
        support.put("verificationDiverged", f.verificationDiverged());
        support.put("verificationTimedOut", f.verificationTimedOut());
        support.put("total", f.total());
        support.put("rankByTotal", rank);
        Map<String, Object> parameters = Map.of("minFailures", settings.minFailures(), "ranking", "total failure facts descending, then device id");
        Subject subject = new Subject(f.deviceId().toString(), null, null);
        boolean enough = f.total() >= settings.minFailures();
        String statement = "In the window the persisted command history records " + f.deadLettered() + " dead-lettered, " + f.ackTimedOut() + " ACK-timed-out and " + f.deliveryUnknown()
                + " delivery-unknown commands for this device, and the current verification state has " + f.verificationDiverged() + " diverged and " + f.verificationTimedOut()
                + " timed-out target(s). These are delivery and verification outcomes; they do not by themselves show whether the fault is in the device, the network or the broker.";
        return new Recommendation(sha("rec", Category.INVESTIGATE_FAILING_DEVICE, f.deviceId(), f.total(), f.refs()), Category.INVESTIGATE_FAILING_DEVICE, enough ? Status.RECOMMENDED : Status.INSUFFICIENT_EVIDENCE, subject,
                enough ? "Investigate repeated command or verification failures for this device" : "Few command failures recorded for this device",
                enough ? statement : "Only " + f.total() + " failure fact(s) in the window, fewer than the " + settings.minFailures() + " required before a recommendation. " + statement, support, parameters, new Window(dbFrom, dbNow), f.refs(),
                Recommendation.NO_EFFECT, now);
    }

    private Recommendation conflict(FailureFactsReader.Conflict c, Instant from, Instant now) {
        boolean enough = c.count() >= settings.minConflicts();
        Map<String, Object> support = new LinkedHashMap<>();
        support.put("suppressions", c.count());
        support.put("byDisposition", c.byDisposition());
        Map<String, Object> parameters = Map.of("minConflicts", settings.minConflicts());
        List<String> refs = new ArrayList<>(c.refs());
        refs.add("rule:" + c.suppressedRule());
        refs.add("rule:" + c.winningRule());
        String statement = "In the window, persisted arbitration records show rule " + c.suppressedRule() + " suppressed " + c.count() + " time(s) in favour of rule " + c.winningRule() + " (" + c.byDisposition()
                + "). Repeated suppression between the same two rules can mean their priorities or targets overlap by design or by accident; the records alone do not say which.";
        return new Recommendation(sha("rec", Category.REVIEW_RULE_CONFLICT, c.suppressedRule(), c.winningRule(), c.count(), c.refs()), Category.REVIEW_RULE_CONFLICT, enough ? Status.RECOMMENDED : Status.INSUFFICIENT_EVIDENCE,
                new Subject(null, c.suppressedRule().toString(), null), enough ? "Review two rules that keep conflicting" : "Few rule suppressions recorded between these rules",
                enough ? statement : "Only " + c.count() + " suppression(s), fewer than the " + settings.minConflicts() + " required before a recommendation. " + statement, support, parameters, new Window(from, now), refs,
                Recommendation.NO_EFFECT, now);
    }

    /** Needs at least two independent groups of facts; one group alone is already reported by its own recommendation. */
    private Recommendation maintenance(UUID device, AnomalyReport report, FailureFactsReader.DeviceFailures failures, Instant now, Instant factFrom) {
        List<String> groups = new ArrayList<>();
        Map<String, Object> support = new LinkedHashMap<>();
        List<String> refs = new ArrayList<>();
        if (failures != null && failures.total() >= settings.minFailures()) {
            groups.add("repeated command or verification failures (" + failures.total() + ")");
            support.put("failureFacts", failures.total());
            refs.addAll(failures.refs());
        }
        TelemetrySeriesReader.Connectivity conn = report.connectivity();
        if (conn != null && "OFFLINE".equalsIgnoreCase(conn.status()) && Duration.between(conn.lastChangedAt(), now).compareTo(Duration.ofHours(settings.staleOfflineHours())) >= 0) {
            long hours = Duration.between(conn.lastChangedAt(), now).toHours();
            groups.add("currently recorded OFFLINE since " + conn.lastChangedAt() + " (" + hours + " hours)");
            support.put("offlineHours", hours);
            refs.add("connectivity:" + device);
        }
        List<Finding> observed = report.findings().stream().filter(f -> f.status() == Finding.Status.OBSERVED && (f.category() == Finding.Category.GAP || f.category() == Finding.Category.FLATLINE)).toList();
        if (!observed.isEmpty()) {
            groups.add("stale or unchanging telemetry (" + observed.stream().map(f -> f.category() + " on " + f.capabilityKey()).distinct().sorted().toList() + ")");
            support.put("telemetryFindings", observed.size());
            observed.forEach(f -> refs.add("finding:" + f.findingId()));
        }
        if (groups.size() < 2) return null;
        support.put("independentFactGroups", groups.size());
        String statement = "Several independent persisted facts point at this device: " + String.join("; ", groups) + ". This suggests a person check the device and its connection. Offline frequency is not available (no connectivity history is persisted), and none of these facts states a physical condition.";
        return new Recommendation(sha("rec", Category.MAINTENANCE_CHECK, device, groups, refs), Category.MAINTENANCE_CHECK, Status.RECOMMENDED, new Subject(device.toString(), null, null), "Consider a maintenance check of this device", statement, support,
                Map.of("minFactGroups", 2, "minFailures", settings.minFailures(), "staleOfflineHours", settings.staleOfflineHours()), new Window(factFrom, now), refs, Recommendation.NO_EFFECT, now);
    }

    static String sha(Object... parts) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Object p : parts) sb.append(p).append('|');
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
