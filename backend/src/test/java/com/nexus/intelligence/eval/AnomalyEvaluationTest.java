package com.nexus.intelligence.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus.intelligence.anomaly.AnomalySettings;
import com.nexus.intelligence.anomaly.CleanedSeries;
import com.nexus.intelligence.anomaly.Detectors;
import com.nexus.intelligence.anomaly.Finding;
import com.nexus.intelligence.anomaly.RawRow;
import com.nexus.intelligence.anomaly.SeriesCleaner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-083, P6-068, P6-081: false positives, false negatives and abstention of each detector on seeded synthetic series, with the families, seeds, amplitudes,
 * truth labels and gating fixed in the corpus BEFORE any result was computed. Every figure is per detector. Rates on synthetic series are not a statement
 * about real sensors, and a point estimate of zero is reported with its 95 percent Wilson upper bound.
 */
class AnomalyEvaluationTest {
    static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    static final UUID DEVICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final Finding.Window WINDOW = new Finding.Window(NOW.minus(Duration.ofHours(24)), NOW.minus(Duration.ofMinutes(120)), NOW);
    static final AnomalySettings SETTINGS = AnomalySettings.defaults();
    static final String[] DETECTORS = {"FLATLINE", "OUTLIER", "SPIKE", "GAP"};
    static final double MEAN = 21.0;
    static final int N = 24 * 60;

    // ---------------------------------------------------------------- generator (version 1, see manifest)

    static List<RawRow> generate(JsonNode f, long seed) {
        Random r = new Random(seed);
        double sd = f.path("sd").asDouble(0.3), step = f.path("step").asDouble(0.1);
        int readings = f.path("readings").asInt(N);
        List<Instant> times = new ArrayList<>();
        if (f.path("irregular").asBoolean(false)) {
            Instant t = WINDOW.from();
            while (t.isBefore(NOW.minus(Duration.ofMinutes(30)))) {
                times.add(t);
                t = t.plusSeconds(10 + (long) (r.nextDouble() * r.nextDouble() * 4000));
            }
        } else if (readings < N) {
            long spacing = Duration.ofHours(24).toSeconds() / readings;
            for (int i = 0; i < readings; i++) times.add(WINDOW.from().plusSeconds(spacing * i));
        } else {
            for (int i = 0; i < N; i++) times.add(WINDOW.from().plusSeconds(60L * i));
        }
        int n = times.size();
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            if (f.has("constantValue")) { v[i] = f.get("constantValue").asDouble(); continue; }
            double noise;
            if (f.path("noise").asText("gaussian").equals("student_t")) {
                double z = r.nextGaussian(), a = r.nextGaussian(), b = r.nextGaussian(), c = r.nextGaussian();
                noise = z / Math.sqrt((a * a + b * b + c * c) / f.path("df").asInt(3));
            } else {
                noise = r.nextGaussian();
            }
            double drift = f.path("driftTotal").asDouble(0.0) * i / Math.max(1, n - 1);
            v[i] = quantise(MEAN + sd * noise + drift, step);
        }
        int recentStart = n - 120;
        double magnitude = f.path("magnitudeSigma").asDouble(0.0) * sd;
        if (f.path("role").asText().equals("INJECTED")) {
            switch (f.get("detector").asText()) {
                case "OUTLIER" -> v[recentStart + 40] = quantise(MEAN + magnitude, step);
                case "SPIKE" -> { for (int i = recentStart + 60; i < n; i++) v[i] = quantise(v[i] + magnitude, step); }
                case "FLATLINE" -> { for (int i = recentStart; i < n; i++) v[i] = quantise(MEAN, step); }
                default -> { /* GAP: rows are dropped below */ }
            }
        }
        List<RawRow> rows = new ArrayList<>();
        Instant silenceFrom = f.has("silenceMinutes") ? NOW.minus(Duration.ofMinutes(f.get("silenceMinutes").asLong())) : null;
        for (int i = 0; i < n; i++) {
            if (silenceFrom != null && !times.get(i).isBefore(silenceFrom)) continue;
            rows.add(new RawRow("s" + seed + "_" + i, times.get(i), "NUMBER", v[i], "CELSIUS"));
        }
        if (f.has("duplicateFraction")) {
            int copies = (int) (rows.size() * f.get("duplicateFraction").asDouble());
            for (int k = 0; k < copies; k++) {
                RawRow o = rows.get(r.nextInt(rows.size()));
                rows.add(new RawRow("dup" + k, o.at(), "NUMBER", o.numeric(), "CELSIUS"));
            }
        }
        if (f.has("futureRows")) {
            for (int k = 0; k < f.get("futureRows").asInt(); k++)
                rows.add(new RawRow("future" + k, NOW.plus(Duration.ofMinutes(f.get("futureMinutes").asLong())).plusSeconds(k), "NUMBER", f.get("futureValue").asDouble(), "CELSIUS"));
        }
        if (f.path("shuffle").asBoolean(false)) Collections.shuffle(rows, r);
        return rows;
    }

    static double quantise(double value, double step) {
        return Math.round(Math.round(value / step) * step * 1000.0) / 1000.0;
    }

    static Map<String, Finding.Status> analyse(List<RawRow> rows) {
        CleanedSeries series = SeriesCleaner.clean(rows, NOW, SETTINGS.skewToleranceSeconds(), false);
        Map<String, Finding.Status> out = new LinkedHashMap<>();
        for (Finding f : Detectors.analyse(DEVICE, "temperature", series, WINDOW, NOW, SETTINGS)) out.put(f.category().name(), f.status());
        return out;
    }

    /** 95 percent Wilson upper bound of a proportion. */
    static double wilsonUpper(int hits, int n) {
        if (n == 0) return 1.0;
        double z = 1.96, p = hits / (double) n, z2 = z * z;
        return Eval.round((p + z2 / (2 * n) + z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n))) / (1 + z2 / n));
    }

    // ---------------------------------------------------------------- the evaluation

    @Test
    void p6_083_p6_068_perDetectorFalsePositivesRecallAndAbstentionOnSeededSyntheticFamilies() throws Exception {
        JsonNode corpus = Eval.corpus("anomaly.json");
        Eval.Suite suite = new Eval.Suite("anomaly");
        Map<String, Map<String, int[]>> statusCounts = new TreeMap<>();        // family -> detector -> [OBSERVED, NOT_OBSERVED, INSUFFICIENT, NOT_APPLICABLE]
        Map<String, int[]> pooledFp = new LinkedHashMap<>();                  // detector -> [flagged, series] over gated normal families
        Map<String, int[]> tp = new LinkedHashMap<>();                        // detector -> [observed, series] over gated injected families
        Map<String, int[]> allSeriesAbstained = new LinkedHashMap<>();        // detector -> [insufficient, series] over EVERY series
        Map<String, Map<String, Integer>> crossDetections = new TreeMap<>();   // injected family -> other detector -> observed
        for (String d : DETECTORS) { pooledFp.put(d, new int[2]); tp.put(d, new int[2]); allSeriesAbstained.put(d, new int[2]); }
        int totalSeries = 0;

        for (JsonNode f : corpus.get("families")) {
            String family = f.get("id").asText(), role = f.get("role").asText();
            boolean gated = f.get("gated").asBoolean();
            int count = f.get("count").asInt();
            long seed0 = f.get("seedStart").asLong();
            Map<String, int[]> counts = new LinkedHashMap<>();
            for (String d : DETECTORS) counts.put(d, new int[4]);
            Map<String, int[]> normalFlagged = new LinkedHashMap<>();
            int abstainedAll = 0, injectedObserved = 0;
            Map<String, Integer> cross = new TreeMap<>();
            List<String> normalFor = new ArrayList<>(), abstainFor = new ArrayList<>();
            if (f.has("normalFor")) f.get("normalFor").forEach(x -> normalFor.add(x.asText()));
            if (f.has("abstainFor")) f.get("abstainFor").forEach(x -> abstainFor.add(x.asText()));
            for (String d : normalFor) normalFlagged.put(d, new int[1]);

            for (int k = 0; k < count; k++) {
                totalSeries++;
                Map<String, Finding.Status> result = analyse(generate(f, seed0 + k));
                boolean allAbstained = !abstainFor.isEmpty();
                for (String d : DETECTORS) {
                    Finding.Status s = result.get(d);
                    counts.get(d)[switch (s) { case OBSERVED -> 0; case NOT_OBSERVED -> 1; case INSUFFICIENT_EVIDENCE -> 2; case NOT_APPLICABLE -> 3; }]++;
                    allSeriesAbstained.get(d)[1]++;
                    if (s == Finding.Status.INSUFFICIENT_EVIDENCE) allSeriesAbstained.get(d)[0]++;
                }
                for (String d : normalFor) if (result.get(d) == Finding.Status.OBSERVED) {
                    normalFlagged.get(d)[0]++;
                    if (gated) suite.fail("anomaly.fpr." + d + "." + family, family + "#" + (seed0 + k), d + " observed on a series with no anomaly");
                }
                for (String d : abstainFor) if (result.get(d) != Finding.Status.INSUFFICIENT_EVIDENCE) {
                    allAbstained = false;
                    if (gated) suite.fail("anomaly.abstention." + family, family + "#" + (seed0 + k), d + " answered " + result.get(d) + " instead of INSUFFICIENT_EVIDENCE");
                }
                if (!abstainFor.isEmpty() && allAbstained) abstainedAll++;
                if (role.equals("INJECTED")) {
                    String target = f.get("detector").asText();
                    if (result.get(target) == Finding.Status.OBSERVED) injectedObserved++;
                    else if (gated) suite.fail("anomaly.recall." + target, family + "#" + (seed0 + k), target + " answered " + result.get(target) + " on an injected anomaly");
                    for (String d : DETECTORS) if (!d.equals(target) && result.get(d) == Finding.Status.OBSERVED) cross.merge(d, 1, Integer::sum);
                }
            }
            Map<String, Object> familyCounts = new LinkedHashMap<>();
            for (String d : DETECTORS) { int[] c = counts.get(d); familyCounts.put(d, Eval.mapOf("observed", c[0], "notObserved", c[1], "insufficientEvidence", c[2], "notApplicable", c[3])); }
            statusCounts.put(family, familyCounts.entrySet().stream().collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), new int[] {0}), Map::putAll));
            suite.characterise("anomaly.family." + family, role + (gated ? " (gated)" : " (not gated)") + ": status counts per detector over " + count + " series", familyCounts);

            for (String d : normalFor) {
                int hits = normalFlagged.get(d)[0];
                suite.add(Eval.Measure.atMost("anomaly.fpr." + d + "." + family, "P6-083 false positives", d + " false-positive rate on " + family + " (contract bound 0.05); 95% Wilson upper bound " + wilsonUpper(hits, count), hits, count, 0.05, 100, gated && f.path("gated").asBoolean(),
                        gated ? "contract threshold" : "proposed bound " + f.path("proposedBound").asDouble(0.05) + ", NOT GATED: characterisation outside the contract's 'clean noisy series'"));
                if (gated && role.equals("NORMAL")) { pooledFp.get(d)[0] += hits; pooledFp.get(d)[1] += count; }
            }
            if (role.equals("ABSTAIN")) {
                suite.add(Eval.Measure.atLeast("anomaly.abstention." + family, "P6-068/081 abstention", "series where every detector in " + abstainFor + " answered INSUFFICIENT_EVIDENCE (contract: 100 percent)", abstainedAll, count, 1.0, 100, gated, "contract threshold"));
            }
            if (role.equals("INJECTED")) {
                String target = f.get("detector").asText();
                if (gated) { tp.get(target)[0] += injectedObserved; tp.get(target)[1] += count; }
                else suite.add(Eval.Measure.atLeast("anomaly.recall." + target + ".weak", "P6-083 false negatives", target + " recall on a weak (5-sigma) injection; informational, proposed 0.9", injectedObserved, count, 0.9, 100, false, "NOT GATED: the contract's recall threshold applies to clear injected anomalies; reported as a sensitivity limit"));
                crossDetections.put(family, cross.entrySet().stream().collect(TreeMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll));
            }
        }

        for (String d : DETECTORS) {
            suite.add(Eval.Measure.atMost("anomaly.fpr." + d + ".pooled", "P6-083 false positives", d + " false-positive rate pooled over every gated clean family (contract bound 0.05); 95% Wilson upper bound " + wilsonUpper(pooledFp.get(d)[0], pooledFp.get(d)[1]),
                    pooledFp.get(d)[0], pooledFp.get(d)[1], 0.05, 100, true, "contract threshold"));
            suite.add(Eval.Measure.atLeast("anomaly.recall." + d, "P6-083 false negatives", d + " recall on clear (10-sigma / flat / 40-minute silence) injected anomalies (contract: at least 0.9)", tp.get(d)[0], tp.get(d)[1], 0.9, 100, true, "contract threshold"));
        }

        Map<String, Object> perDetector = new LinkedHashMap<>();
        for (String d : DETECTORS) {
            int fpHits = pooledFp.get(d)[0], fpN = pooledFp.get(d)[1], hits = tp.get(d)[0], inj = tp.get(d)[1];
            perDetector.put(d, Eval.mapOf(
                    "precisionOnThisCorpusMix", hits + fpHits == 0 ? null : Eval.round(hits / (double) (hits + fpHits)),
                    "recall", Eval.round(hits / (double) inj), "falseNegativeRate", Eval.round(1 - hits / (double) inj),
                    "falsePositiveRate", Eval.round(fpHits / (double) fpN), "falsePositiveRateWilsonUpper95", wilsonUpper(fpHits, fpN),
                    "abstentionRateOverAllSeries", Eval.round(allSeriesAbstained.get(d)[0] / (double) allSeriesAbstained.get(d)[1]),
                    "note", "precision depends on the mix of injected and clean series in this corpus; it is not a property of a deployment"));
        }
        suite.characterise("anomaly.per_detector", "precision, recall, false-negative rate, false-positive rate (with Wilson upper bound) and abstention rate per detector", perDetector);
        suite.characterise("anomaly.cross_detections", "other detectors that also observed an injected anomaly (informational: they are not false positives of that detector)", new LinkedHashMap<>(crossDetections));
        suite.characterise("anomaly.missing_timestamp", "missing-timestamp cases", Eval.mapOf("status", "NOT_APPLICABLE",
                "reason", "telemetry_history.timestamp is NOT NULL and the ingestion request requires it, so a persisted series cannot contain a reading without a time; a row without one is refused before it can be analysed"));
        suite.run("series", totalSeries).run("generatorVersion", 1);
        suite.writeAndGate();
        assertThat(totalSeries).isEqualTo(2300);
    }
}
