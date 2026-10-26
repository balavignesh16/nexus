package com.nexus.intelligence.trend;

import com.nexus.intelligence.anomaly.CleanedSeries;
import com.nexus.intelligence.anomaly.Sample;
import com.nexus.intelligence.trend.TrendResult.Established;
import com.nexus.intelligence.trend.TrendResult.LimitAssessment;
import com.nexus.intelligence.trend.TrendResult.Rate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, deterministic trend and rate-of-change analysis; no I/O, no clock, no randomness, no model.
 *
 * <p>Method. The descriptive direction comes from the Theil-Sen slope (the median of the pairwise slopes, robust to outliers) multiplied by the span: the fitted
 * change must exceed BOTH the sensor resolution (the smallest step between distinct readings, or a configured value) and {@code noiseBands} robust deviations of the
 * residuals, otherwise the series is STABLE. A single large step that carries most of the net change is labelled SUDDEN_STEP rather than a gradual trend. A trend is
 * ESTABLISHED only for at least {@code minEstablishedSamples} timestamped readings over {@code minEstablishedSpanSeconds}, regularly sampled (no gaps, bounded
 * interval variation), with a significant Mann-Kendall test, that are not a single step. The rate is the Theil-Sen slope per minute and exists only with timestamps.
 * Readings without timestamps give a direction and nothing else: no rate and no established trend.
 */
public final class TrendAnalyzer {
    public static final String BASIS_TIMESTAMPED = "TIMESTAMPED";
    public static final String BASIS_VALUES_ONLY = "VALUES_ONLY";
    private static final String RATE_METHOD = "THEIL_SEN_MEDIAN_OF_PAIRWISE_SLOPES";

    private TrendAnalyzer() {}

    /** Ordered readings with no timestamps (for example three numbers a person typed). */
    public static TrendResult analyseValues(List<Double> values, String unit, HardLimit limit, Double resolutionOverride, TrendSettings settings, Instant at) {
        double[] v = new double[values.size()];
        double[] t = new double[values.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = values.get(i);
            t[i] = i;
        }
        return compute(t, v, false, unit, null, null, null, limit, resolutionOverride, settings, at);
    }

    /** Cleaned, time-ordered, de-duplicated samples (see SeriesCleaner). */
    public static TrendResult analyse(List<Sample> samples, String unit, Instant from, Instant to, CleanedSeries.Quality quality, HardLimit limit, Double resolutionOverride, TrendSettings settings, Instant at) {
        double[] v = new double[samples.size()];
        double[] t = new double[samples.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = samples.get(i).value();
            t[i] = samples.get(i).at().toEpochMilli() / 1000.0;
        }
        return compute(t, v, true, unit, from, to, quality, limit, resolutionOverride, settings, at);
    }

    public static TrendResult notApplicable(String reason, String unit, Instant from, Instant to, Map<String, Object> quality, Instant at) {
        return new TrendResult(TrendDirection.NOT_APPLICABLE, BASIS_TIMESTAMPED, unit, 0, from, to, null, null, null, null, null, null, new Established(false, List.of("NOT_APPLICABLE"), null, 0), "NONE", 0, null,
                quality, null, List.of(), standardLimits(), reason, at);
    }

    public static TrendResult insufficient(String reason, String unit, int samples, Instant from, Instant to, Map<String, Object> quality, HardLimit limit, Double last, Instant at) {
        List<String> notes = new ArrayList<>();
        notes.add("The platform looked at the stored readings and cannot describe a direction from them.");
        if (limit == null) notes.add("No limit was configured, so this analysis makes no statement about safety.");
        return new TrendResult(TrendDirection.INSUFFICIENT_EVIDENCE, BASIS_TIMESTAMPED, unit, samples, from, to, null, null, last, null, null, null, new Established(false, List.of("INSUFFICIENT_SAMPLES"), null, 0), "NONE", 0, null,
                quality, assessLimit(limit, unit, last), notes, standardLimits(), reason, at);
    }

    private static TrendResult compute(double[] t, double[] v, boolean timestamped, String unit, Instant from, Instant to, CleanedSeries.Quality cleaning, HardLimit limit, Double resolutionOverride,
                                       TrendSettings s, Instant at) {
        int n = v.length;
        String basis = timestamped ? BASIS_TIMESTAMPED : BASIS_VALUES_ONLY;
        Double last = n == 0 ? null : v[n - 1];
        Map<String, Object> quality = new LinkedHashMap<>();
        if (cleaning != null) quality.putAll(cleaning.asMap());
        if (n < s.minDescriptiveSamples()) {
            TrendResult r = insufficient(n + " reading(s) are available and at least " + s.minDescriptiveSamples() + " are needed to describe a direction", unit, n, from, to, quality, limit, last, at);
            return new TrendResult(r.direction(), basis, r.unit(), r.sampleCount(), r.windowFrom(), r.windowTo(), r.spanSeconds(), n == 0 ? null : v[0], r.lastValue(), n < 2 ? null : v[n - 1] - v[0], null, null,
                    r.established(), "NONE", 0, null, quality, r.limit(), r.notes(), r.limits(), r.reason(), at);
        }
        for (double x : v) if (!Double.isFinite(x)) throw new IllegalArgumentException("readings must be finite numbers");

        double span = t[n - 1] - t[0];
        double resolution = resolutionOverride != null && resolutionOverride > 0 ? resolutionOverride : estimateResolution(v);
        int[] idx = strideIndices(n, s.maxPoints());
        double[] pt = new double[idx.length], pv = new double[idx.length];
        for (int i = 0; i < idx.length; i++) {
            pt[i] = t[idx[i]];
            pv[i] = v[idx[i]];
        }
        double slope = theilSen(pt, pv);
        double intercept = median(residualBase(pt, pv, slope));
        double[] residuals = new double[pv.length];
        for (int i = 0; i < pv.length; i++) residuals[i] = pv[i] - (intercept + slope * pt[i]);
        double sigma = 1.4826 * mad(residuals);
        double fitted = slope * span;
        double band = Math.max(resolution, s.noiseBands() * sigma);
        double net = v[n - 1] - v[0];

        // both the fitted change and the observed net change must clear the band: a robust fit alone can describe more change than the readings actually show
        TrendDirection direction = Math.abs(fitted) > band && Math.abs(net) > band ? (fitted > 0 ? TrendDirection.INCREASING : TrendDirection.DECREASING) : TrendDirection.STABLE;
        if (resolution == 0 && sigma == 0 && net == 0) direction = TrendDirection.STABLE;

        // one large step carrying most of the net change is labelled as a step, whatever the fitted slope says
        String shape = "NONE";
        double[] diffs = new double[n - 1];
        for (int i = 0; i < n - 1; i++) diffs[i] = v[i + 1] - v[i];
        double maxJump = 0;
        for (double d : diffs) if (Math.abs(d) > Math.abs(maxJump)) maxJump = d;
        double stepThreshold = Math.max(Math.max(2 * resolution, 5 * 1.4826 * mad(diffs)), 1e-12);
        boolean step = n >= 5 && Math.abs(maxJump) > Math.max(band, stepThreshold) && Math.abs(net) > band && Math.signum(net) == Math.signum(maxJump) && Math.abs(maxJump) >= 0.6 * Math.abs(net);
        List<String> notes = new ArrayList<>();
        if (step && direction == TrendDirection.STABLE) direction = net > 0 ? TrendDirection.INCREASING : TrendDirection.DECREASING;
        if (direction != TrendDirection.STABLE) shape = step && Math.signum(maxJump) == Math.signum(direction == TrendDirection.INCREASING ? 1 : -1) ? "SUDDEN_STEP" : "GRADUAL";

        // regularity and gaps (timestamped only)
        int gaps = 0;
        double cv = 0;
        if (timestamped && n >= 3) {
            double[] intervals = new double[n - 1];
            for (int i = 0; i < n - 1; i++) intervals[i] = t[i + 1] - t[i];
            double med = median(intervals);
            double mean = Arrays.stream(intervals).average().orElse(0);
            double var = Arrays.stream(intervals).map(x -> (x - mean) * (x - mean)).sum() / intervals.length;
            cv = mean > 0 ? Math.sqrt(var) / mean : 0;
            for (double x : intervals) if (med > 0 && x > s.gapFactor() * med) gaps++;
        }
        quality.put("distinctValues", Arrays.stream(v).distinct().count());
        quality.put("pointsUsed", idx.length);
        if (timestamped) {
            quality.put("intervalVariation", round(cv));
            quality.put("gapsPresent", gaps);
            quality.put("irregularSampling", cv > s.maxIntervalCv());
        }
        quality.put("coarseResolution", resolution > 0 && Math.abs(fitted) < 5 * resolution);
        quality.put("inputOrderIndependent", timestamped);

        // statistical establishment
        Double z = idx.length >= 3 ? mannKendallZ(pv) : null;
        List<String> reasons = new ArrayList<>();
        if (!timestamped) reasons.add("NO_TIMESTAMPS");
        if (n < s.minEstablishedSamples()) reasons.add("INSUFFICIENT_SAMPLES");
        if (timestamped && span < s.minEstablishedSpanSeconds()) reasons.add("SPAN_TOO_SHORT");
        if (timestamped && cv > s.maxIntervalCv()) reasons.add("IRREGULAR_SAMPLING");
        if (timestamped && gaps > 0) reasons.add("GAPS_PRESENT");
        if (direction == TrendDirection.STABLE) reasons.add("NOT_DIRECTIONAL");
        if ("SUDDEN_STEP".equals(shape)) reasons.add("STEP_NOT_A_GRADUAL_TREND");
        if (z == null || Math.abs(z) < s.significanceZ()) reasons.add("NOT_SIGNIFICANT");
        else if (direction == TrendDirection.INCREASING && z < 0 || direction == TrendDirection.DECREASING && z > 0) reasons.add("TEST_DISAGREES");
        boolean established = reasons.isEmpty();

        Rate rate = null;
        if (timestamped && span > 0) rate = new Rate(slope * 60.0, (unit == null || unit.isBlank() ? "units" : unit) + "/min", RATE_METHOD, established);

        if (direction == TrendDirection.INCREASING || direction == TrendDirection.DECREASING)
            notes.add("The direction is descriptive: the fitted change is larger than the sensor resolution and than the noise in these readings.");
        else notes.add("The readings did not change by more than the sensor resolution and the noise, so they are described as stable.");
        if (resolutionOverride == null && Arrays.stream(v).distinct().count() <= 2 && net != 0)
            notes.add("Only two distinct values were seen, so the sensor resolution cannot be estimated: the observed step is treated as one quantum of the sensor. Configure the resolution to change this.");
        if (!timestamped) notes.add("No rate is reported because these readings carry no timestamps.");
        if (!established) notes.add("The direction is not statistically established (" + String.join(", ", reasons) + "); no trend strength is claimed.");
        if (limit == null) notes.add("No limit was configured, so this analysis makes no statement about safety.");
        notes.add("Acceleration and trend strength are not reported.");
        if (cleaning != null && cleaning.futureDated() > 0) notes.add(cleaning.futureDated() + " reading(s) stamped in the future were excluded.");

        return new TrendResult(direction, basis, unit, n, from, to, timestamped ? span : null, v[0], v[n - 1], net, fitted, rate, new Established(established, reasons, z, idx.length), shape, resolution,
                round(sigma), quality, assessLimit(limit, unit, v[n - 1]), notes, standardLimits(), null, at);
    }

    static LimitAssessment assessLimit(HardLimit limit, String unit, Double last) {
        if (limit == null) return null;
        String have = unit == null ? "" : unit.strip();
        if (last == null || !have.equalsIgnoreCase(limit.unit().strip()))
            return new LimitAssessment("UNIT_MISMATCH", limit.value(), limit.unit(), limit.comparison().name(), last, null);
        return new LimitAssessment(limit.met(last) ? "MET" : "NOT_MET", limit.value(), limit.unit(), limit.comparison().name(), last, limit.value() - last);
    }

    private static List<String> standardLimits() {
        return List.of("This describes stored readings; it does not show the cause of a change.",
                "A limit is a value a person configured; a rule built from it is a software rule, not a certified hardware emergency cutoff.");
    }

    // ------------------------------------------------------------------ statistics

    /** The smallest positive step between distinct readings: the apparent measurement resolution (0 when all readings are equal). */
    static double estimateResolution(double[] v) {
        double[] sorted = Arrays.stream(v).distinct().sorted().toArray();
        double best = Double.MAX_VALUE;
        for (int i = 1; i < sorted.length; i++) best = Math.min(best, sorted[i] - sorted[i - 1]);
        return best == Double.MAX_VALUE ? 0 : best;
    }

    static int[] strideIndices(int n, int max) {
        if (n <= max) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) all[i] = i;
            return all;
        }
        int[] out = new int[max];
        for (int i = 0; i < max; i++) out[i] = (int) Math.round(i * (n - 1) / (double) (max - 1));
        return out;
    }

    static double theilSen(double[] t, double[] v) {
        List<Double> slopes = new ArrayList<>();
        for (int i = 0; i < t.length; i++)
            for (int j = i + 1; j < t.length; j++) {
                double dt = t[j] - t[i];
                if (dt > 0) slopes.add((v[j] - v[i]) / dt);
            }
        if (slopes.isEmpty()) return 0;
        double[] a = slopes.stream().mapToDouble(Double::doubleValue).toArray();
        return median(a);
    }

    private static double[] residualBase(double[] t, double[] v, double slope) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] - slope * t[i];
        return out;
    }

    static double median(double[] a) {
        if (a.length == 0) return 0;
        double[] s = a.clone();
        Arrays.sort(s);
        int m = s.length / 2;
        return s.length % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2.0;
    }

    static double mad(double[] a) {
        double m = median(a);
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) d[i] = Math.abs(a[i] - m);
        return median(d);
    }

    /** Mann-Kendall z with the tie correction and continuity correction; positive means rising. */
    static double mannKendallZ(double[] x) {
        int n = x.length;
        double s = 0;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) s += Math.signum(x[j] - x[i]);
        Map<Double, Integer> ties = new java.util.HashMap<>();
        for (double d : x) ties.merge(d, 1, Integer::sum);
        double var = n * (n - 1.0) * (2.0 * n + 5.0);
        for (int tcount : ties.values()) if (tcount > 1) var -= tcount * (tcount - 1.0) * (2.0 * tcount + 5.0);
        var /= 18.0;
        if (var <= 0) return 0;
        if (s > 0) return (s - 1) / Math.sqrt(var);
        if (s < 0) return (s + 1) / Math.sqrt(var);
        return 0;
    }

    private static double round(double x) {
        return Math.round(x * 1e6) / 1e6;
    }
}
