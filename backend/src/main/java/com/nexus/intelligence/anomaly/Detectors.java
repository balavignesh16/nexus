package com.nexus.intelligence.anomaly;

import com.nexus.intelligence.anomaly.Finding.Category;
import com.nexus.intelligence.anomaly.Finding.Status;
import com.nexus.intelligence.anomaly.Finding.Window;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The four deterministic detectors, as pure functions of one cleaned series, the window and the settings. No clock, no database, no randomness: the same
 * readings in any order give the same findings. Each detector estimates its scale from the series' own baseline (readings before the recent window), so a
 * thermometer and a power meter are judged by their own variation, and each refuses (INSUFFICIENT_EVIDENCE) rather than guess when the baseline is too small,
 * does not vary, or the cadence is not regular enough to define silence.
 */
public final class Detectors {
    private Detectors() {}

    public static List<Finding> analyse(UUID device, String capability, CleanedSeries series, Window window, Instant evaluatedAt, AnomalySettings s) {
        Context c = new Context(device, capability, series, window, evaluatedAt, s);
        List<Finding> out = new ArrayList<>();
        if (series.applicability() != CleanedSeries.Applicability.NUMERIC) {
            String reason = switch (series.applicability()) {
                case NOT_NUMERIC -> "NOT_A_CONTINUOUS_NUMERIC_CAPABILITY";
                case MIXED_VALUE_TYPES -> "MIXED_VALUE_TYPES";
                case MIXED_UNITS -> "MIXED_UNITS";
                default -> "NO_READINGS_IN_WINDOW";
            };
            Status status = series.applicability() == CleanedSeries.Applicability.NOT_NUMERIC ? Status.NOT_APPLICABLE : Status.INSUFFICIENT_EVIDENCE;
            for (Category cat : Category.values()) out.add(c.finish(cat, status, reason, Map.of(), List.of(), Map.of(), unknownOnly(reason)));
            return out;
        }
        out.add(flatline(c));
        out.add(outlier(c));
        out.add(spike(c));
        out.add(gap(c));
        return out;
    }

    private static List<String> unknownOnly(String reason) {
        return List.of("Nothing can be said about this capability's behaviour from the persisted readings (" + reason + ").");
    }

    // ---------------------------------------------------------------- shared

    private static final class Context {
        final UUID device;
        final String capability;
        final CleanedSeries series;
        final Window window;
        final Instant evaluatedAt;
        final AnomalySettings s;
        final List<Sample> baseline = new ArrayList<>();
        final List<Sample> recent = new ArrayList<>();
        final String digest;

        Context(UUID device, String capability, CleanedSeries series, Window window, Instant evaluatedAt, AnomalySettings s) {
            this.device = device;
            this.capability = capability;
            this.series = series;
            this.window = window;
            this.evaluatedAt = evaluatedAt;
            this.s = s;
            for (Sample x : series.samples()) {
                if (x.at().isBefore(window.from()) || !x.at().isBefore(window.to())) continue; // half-open [from, to)
                if (x.at().isBefore(window.split())) baseline.add(x);
                else recent.add(x);
            }
            StringBuilder sb = new StringBuilder();
            for (Sample x : series.samples()) sb.append(x.at().toEpochMilli()).append('|').append(Double.toString(x.value())).append(';');
            this.digest = sha256(sb.toString());
        }

        Finding finish(Category category, Status status, String reason, Map<String, Object> statistics, List<Map<String, Object>> measurements, Map<String, Object> rule,
                       List<String> known) {
            return finish(category, status, reason, statistics, measurements, rule, known, List.of());
        }

        Finding finish(Category category, Status status, String reason, Map<String, Object> statistics, List<Map<String, Object>> measurements, Map<String, Object> rule,
                       List<String> known, List<String> extraUncertain) {
            Map<String, Object> samples = new LinkedHashMap<>();
            samples.put("baseline", baseline.size());
            samples.put("recent", recent.size());
            samples.put("kept", series.quality().kept());
            List<String> refs = new ArrayList<>();
            refs.add("telemetry:" + device + "/" + capability + "?from=" + window.from() + "&to=" + window.to());
            for (Map<String, Object> m : measurements) if (m.get("rowId") != null) refs.add("telemetry-row:" + m.get("rowId"));
            List<String> uncertain = new ArrayList<>(extraUncertain);
            uncertain.add("This is an observation about persisted readings. It does not show a hardware fault, and no probability or confidence is claimed.");
            String id = sha256(device + "|" + capability + "|" + category + "|" + window.from() + "|" + window.split() + "|" + window.to() + "|" + digest);
            return new Finding(id, device, capability, category, status, reason, window, series.quality().asMap(), samples, statistics, rule, measurements, refs, known, uncertain, false,
                    evaluatedAt);
        }

        Finding insufficient(Category category, String reason, Map<String, Object> statistics, Map<String, Object> rule) {
            return finish(category, Status.INSUFFICIENT_EVIDENCE, reason, statistics, List.of(), rule, List.of("The persisted readings do not allow a verdict (" + reason + ")."));
        }
    }

    private static List<Double> values(List<Sample> xs) {
        return xs.stream().map(Sample::value).toList();
    }

    private static List<Double> intervalsSeconds(List<Sample> xs) {
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < xs.size(); i++) out.add(Duration.between(xs.get(i - 1).at(), xs.get(i).at()).toMillis() / 1000.0);
        return out;
    }

    /** The smallest positive step between distinct baseline readings: the series' own quantisation. Zero when fewer than two distinct values exist. */
    private static double resolution(List<Sample> baseline) {
        TreeSet<Double> distinct = new TreeSet<>(values(baseline));
        double best = Double.MAX_VALUE;
        Double prev = null;
        for (Double d : distinct) {
            if (prev != null) best = Math.min(best, d - prev);
            prev = d;
        }
        return best == Double.MAX_VALUE ? 0.0 : best;
    }

    private static Map<String, Object> measurement(Sample x, Double score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rowId", x.id());
        m.put("at", x.at().toString());
        m.put("value", x.value());
        if (score != null) m.put("score", round(score));
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static Map<String, Object> rule(String name, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", name);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ---------------------------------------------------------------- flatline

    static Finding flatline(Context c) {
        Map<String, Object> rule = rule("FLATLINE: every recent reading is the same within the series' own reading step, while its baseline varies",
                "minBaselineSamples", c.s.minBaselineSamples(), "minRecentSamples", c.s.minRecentSamples(), "minBaselineDistinctValues", 3, "minRecentSpanCadences", 5);
        if (c.baseline.size() < c.s.minBaselineSamples()) return c.insufficient(Category.FLATLINE, "BASELINE_TOO_SMALL", Map.of("baselineSamples", c.baseline.size()), rule);
        if (c.recent.size() < c.s.minRecentSamples()) return c.insufficient(Category.FLATLINE, "RECENT_TOO_SMALL", Map.of("recentSamples", c.recent.size()), rule);
        long distinct = values(c.baseline).stream().distinct().count();
        if (distinct < 3) return c.insufficient(Category.FLATLINE, "BASELINE_NOT_VARYING", Map.of("baselineDistinctValues", distinct), rule);
        double cadence = RobustStats.median(intervalsSeconds(c.baseline));
        double spanSeconds = Duration.between(c.recent.getFirst().at(), c.recent.getLast().at()).toMillis() / 1000.0;
        if (spanSeconds < 5 * cadence) return c.insufficient(Category.FLATLINE, "RECENT_SPAN_TOO_SHORT", Map.of("recentSpanSeconds", round(spanSeconds), "baselineCadenceSeconds", round(cadence)), rule);
        double resolution = resolution(c.baseline);
        double min = values(c.recent).stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double max = values(c.recent).stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("baselineMedian", round(RobustStats.median(values(c.baseline))));
        stats.put("baselineDistinctValues", distinct);
        stats.put("readingStep", resolution);
        stats.put("recentMin", min);
        stats.put("recentMax", max);
        stats.put("recentSpanSeconds", round(spanSeconds));
        stats.put("baselineCadenceSeconds", round(cadence));
        boolean flat = (max - min) < resolution;
        if (!flat) return c.finish(Category.FLATLINE, Status.NOT_OBSERVED, "RECENT_READINGS_VARY", stats, List.of(), rule,
                List.of("The recent readings differ by " + round(max - min) + ", which is at least the series' reading step of " + resolution + "."));
        List<Map<String, Object>> m = List.of(measurement(c.recent.getFirst(), null), measurement(c.recent.getLast(), null));
        return c.finish(Category.FLATLINE, Status.OBSERVED, "IDENTICAL_RECENT_READINGS", stats, m, rule,
                List.of(c.recent.size() + " persisted readings over " + round(spanSeconds) + " seconds all equal " + min + ", although the same capability varied across " + distinct + " distinct values earlier in the window."),
                List.of("An unchanging value can mean a stuck sensor or a quantity that really did not change; the readings alone cannot tell them apart."));
    }

    // ---------------------------------------------------------------- outliers

    static Finding outlier(Context c) {
        Map<String, Object> rule = rule("OUTLIER: modified z-score 0.6745 |x - median| / MAD against the baseline exceeds k (mean-absolute-deviation form when MAD is 0)",
                "k", c.s.outlierK(), "minBaselineSamples", c.s.minBaselineSamples(), "minDeviationReadingSteps", 3);
        if (c.baseline.size() < c.s.minBaselineSamples()) return c.insufficient(Category.OUTLIER, "BASELINE_TOO_SMALL", Map.of("baselineSamples", c.baseline.size()), rule);
        if (c.recent.isEmpty()) return c.insufficient(Category.OUTLIER, "NO_RECENT_READINGS", Map.of(), rule);
        RobustStats.Scale scale = RobustStats.scale(values(c.baseline));
        if (!scale.defined()) return c.insufficient(Category.OUTLIER, "BASELINE_NOT_VARYING", Map.of("baselineMedian", scale.median()), rule);
        double resolution = resolution(c.baseline);
        List<Map<String, Object>> flagged = new ArrayList<>();
        List<Sample> flaggedSamples = new ArrayList<>();
        for (Sample x : c.recent) {
            double z = scale.score(x.value());
            if (z > c.s.outlierK() && Math.abs(x.value() - scale.median()) >= 3 * resolution) {
                flaggedSamples.add(x);
            }
        }
        flaggedSamples.sort(Comparator.comparingDouble((Sample x) -> -scale.score(x.value())).thenComparing(Sample::at));
        for (Sample x : flaggedSamples.stream().limit(c.s.maxFindingMeasurements()).toList()) flagged.add(measurement(x, scale.score(x.value())));
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("baselineMedian", round(scale.median()));
        stats.put("baselineMad", round(scale.mad()));
        stats.put("scaleForm", scale.form());
        stats.put("recentReadings", c.recent.size());
        stats.put("flaggedReadings", flaggedSamples.size());
        if (flaggedSamples.isEmpty()) return c.finish(Category.OUTLIER, Status.NOT_OBSERVED, "ALL_RECENT_READINGS_WITHIN_BASELINE", stats, List.of(), rule,
                List.of("None of the " + c.recent.size() + " recent readings is more than " + c.s.outlierK() + " robust deviations from the baseline median " + round(scale.median()) + "."));
        return c.finish(Category.OUTLIER, Status.OBSERVED, "READINGS_BEYOND_BASELINE_SPREAD", stats, flagged, rule,
                List.of(flaggedSamples.size() + " of " + c.recent.size() + " recent readings are more than " + c.s.outlierK() + " robust deviations from the baseline median " + round(scale.median()) + "."),
                List.of("A real change in the measured quantity looks the same as a measurement error in this data."));
    }

    // ---------------------------------------------------------------- spikes

    static Finding spike(Context c) {
        Map<String, Object> rule = rule("SPIKE: modified z-score of the change between consecutive readings (pairs no wider than 3 reporting intervals) exceeds k",
                "k", c.s.spikeK(), "maxPairIntervals", 3, "minBaselineChanges", Math.max(5, c.s.minBaselineSamples() / 2), "minDeviationReadingSteps", 3);
        if (c.baseline.size() < c.s.minBaselineSamples()) return c.insufficient(Category.SPIKE, "BASELINE_TOO_SMALL", Map.of("baselineSamples", c.baseline.size()), rule);
        List<Double> baseIntervals = intervalsSeconds(c.baseline);
        double cadence = RobustStats.median(baseIntervals);
        double maxPair = 3 * cadence;
        List<Double> baseDiffs = new ArrayList<>();
        List<Sample> all = c.series.samples();
        List<Sample[]> recentPairs = new ArrayList<>();
        for (int i = 1; i < all.size(); i++) {
            Sample a = all.get(i - 1), b = all.get(i);
            if (b.at().isBefore(c.window.from()) || !b.at().isBefore(c.window.to())) continue;
            if (Duration.between(a.at(), b.at()).toMillis() / 1000.0 > maxPair) continue;
            if (b.at().isBefore(c.window.split())) baseDiffs.add(b.value() - a.value());
            else recentPairs.add(new Sample[] {a, b});
        }
        int minChanges = Math.max(5, c.s.minBaselineSamples() / 2);
        if (baseDiffs.size() < minChanges) return c.insufficient(Category.SPIKE, "BASELINE_CHANGES_TOO_FEW", Map.of("baselineChanges", baseDiffs.size()), rule);
        if (recentPairs.isEmpty()) return c.insufficient(Category.SPIKE, "NO_RECENT_CONSECUTIVE_READINGS", Map.of(), rule);
        RobustStats.Scale scale = RobustStats.scale(baseDiffs);
        if (!scale.defined()) return c.insufficient(Category.SPIKE, "BASELINE_NOT_VARYING", Map.of("baselineMedianChange", scale.median()), rule);
        double resolution = resolution(c.baseline);
        List<Sample[]> flagged = new ArrayList<>();
        for (Sample[] p : recentPairs) {
            double d = p[1].value() - p[0].value();
            if (scale.score(d) > c.s.spikeK() && Math.abs(d - scale.median()) >= 3 * resolution) flagged.add(p);
        }
        flagged.sort(Comparator.comparingDouble((Sample[] p) -> -scale.score(p[1].value() - p[0].value())).thenComparing(p -> p[1].at()));
        List<Map<String, Object>> m = new ArrayList<>();
        for (Sample[] p : flagged.stream().limit(c.s.maxFindingMeasurements()).toList()) {
            Map<String, Object> row = measurement(p[1], scale.score(p[1].value() - p[0].value()));
            row.put("previousRowId", p[0].id());
            row.put("previousValue", p[0].value());
            row.put("change", round(p[1].value() - p[0].value()));
            m.add(row);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("baselineMedianChange", round(scale.median()));
        stats.put("baselineChangeMad", round(scale.mad()));
        stats.put("scaleForm", scale.form());
        stats.put("baselineChanges", baseDiffs.size());
        stats.put("recentChanges", recentPairs.size());
        stats.put("flaggedChanges", flagged.size());
        stats.put("pairLimitSeconds", round(maxPair));
        if (flagged.isEmpty()) return c.finish(Category.SPIKE, Status.NOT_OBSERVED, "RECENT_CHANGES_WITHIN_BASELINE", stats, List.of(), rule,
                List.of("None of the " + recentPairs.size() + " recent changes between consecutive readings exceeds " + c.s.spikeK() + " robust deviations of the baseline changes."));
        return c.finish(Category.SPIKE, Status.OBSERVED, "ABRUPT_CHANGE_BETWEEN_READINGS", stats, m, rule,
                List.of(flagged.size() + " of " + recentPairs.size() + " recent changes between consecutive readings exceed " + c.s.spikeK() + " robust deviations of the baseline changes."),
                List.of("An abrupt change can be a real event, a level shift or a measurement glitch; the readings alone cannot tell them apart."));
    }

    // ---------------------------------------------------------------- gaps

    static Finding gap(Context c) {
        Map<String, Object> rule = rule("GAP: silence longer than gapFactor x the device's own median reporting interval (and at least minGapSeconds), when the baseline cadence is regular",
                "gapFactor", c.s.gapFactor(), "minGapSeconds", c.s.minGapSeconds(), "minBaselineIntervals", 8, "maxCadenceIrregularity", 0.5);
        if (c.baseline.size() < 9) return c.insufficient(Category.GAP, "BASELINE_TOO_SMALL", Map.of("baselineSamples", c.baseline.size()), rule);
        List<Double> intervals = intervalsSeconds(c.baseline);
        double cadence = RobustStats.median(intervals);
        if (cadence <= 0) return c.insufficient(Category.GAP, "CADENCE_UNDEFINED", Map.of(), rule);
        double irregularity = RobustStats.mad(intervals) / cadence;
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("baselineCadenceSeconds", round(cadence));
        stats.put("cadenceIrregularity", round(irregularity));
        if (irregularity > 0.5) return c.insufficient(Category.GAP, "IRREGULAR_CADENCE", stats, rule);
        double limit = Math.max(c.s.gapFactor() * cadence, c.s.minGapSeconds());
        stats.put("silenceLimitSeconds", round(limit));
        List<Map<String, Object>> gaps = new ArrayList<>();
        List<Sample> all = c.series.samples();
        for (int i = 1; i < all.size(); i++) {
            Sample a = all.get(i - 1), b = all.get(i);
            if (b.at().isBefore(c.window.split()) || !b.at().isBefore(c.window.to())) continue;
            double seconds = Duration.between(a.at(), b.at()).toMillis() / 1000.0;
            if (seconds > limit) {
                Map<String, Object> g = measurement(b, null);
                g.put("previousRowId", a.id());
                g.put("previousAt", a.at().toString());
                g.put("silenceSeconds", round(seconds));
                gaps.add(g);
            }
        }
        Sample last = all.isEmpty() ? null : all.getLast();
        double tail = last == null ? 0 : Duration.between(last.at(), c.window.to()).toMillis() / 1000.0;
        boolean tailSilent = last != null && tail > limit;
        stats.put("secondsSinceLastReading", round(tail));
        stats.put("interiorGaps", gaps.size());
        List<Map<String, Object>> m = new ArrayList<>(gaps.stream().limit(c.s.maxFindingMeasurements()).toList());
        if (tailSilent) {
            Map<String, Object> t = measurement(last, null);
            t.put("silenceSeconds", round(tail));
            t.put("kind", "NO_READING_SINCE");
            m.add(t);
        }
        List<String> uncertain = List.of("Missing readings do not show that the device is offline: connectivity is a separate fact, and a device can be reachable while a capability is not reported.",
                "The silence is measured against the backend's evaluation time but the readings carry the device's own timestamps, so a device clock error shifts it.");
        if (gaps.isEmpty() && !tailSilent) return c.finish(Category.GAP, Status.NOT_OBSERVED, "REPORTING_CONTINUOUS", stats, List.of(), rule,
                List.of("No silence longer than " + round(limit) + " seconds (" + c.s.gapFactor() + " reporting intervals) in the recent window, and the latest reading is " + round(tail) + " seconds before the evaluation time."));
        List<String> known = new ArrayList<>();
        if (!gaps.isEmpty()) known.add(gaps.size() + " interval(s) between persisted readings exceed " + round(limit) + " seconds in the recent window.");
        if (tailSilent) known.add("No reading with an event time after " + last.at() + " is persisted; that is " + round(tail) + " seconds before the evaluation time, against a normal interval of " + round(cadence) + " seconds.");
        return c.finish(Category.GAP, Status.OBSERVED, tailSilent ? "NO_RECENT_READING" : "SILENCE_BETWEEN_READINGS", stats, m, rule, known, uncertain);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
