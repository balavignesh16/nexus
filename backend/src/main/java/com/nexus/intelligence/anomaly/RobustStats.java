package com.nexus.intelligence.anomaly;

import java.util.Arrays;
import java.util.List;

/** Order statistics with no dependency on input order: the same values give the same result in any sequence. */
public final class RobustStats {
    private RobustStats() {}

    public static double median(List<Double> values) {
        double[] a = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = a.length;
        if (n == 0) throw new IllegalArgumentException("no values");
        return n % 2 == 1 ? a[n / 2] : (a[n / 2 - 1] + a[n / 2]) / 2.0;
    }

    /** Median absolute deviation about the median. */
    public static double mad(List<Double> values) {
        double m = median(values);
        return median(values.stream().map(v -> Math.abs(v - m)).toList());
    }

    public static double meanAbsoluteDeviation(List<Double> values) {
        double m = median(values);
        return values.stream().mapToDouble(v -> Math.abs(v - m)).average().orElse(0.0);
    }

    /** Nearest-rank percentile (p in (0,100]): the smallest value with at least p percent of the sample at or below it. */
    public static double percentile(List<Double> values, double p) {
        double[] a = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        if (a.length == 0) throw new IllegalArgumentException("no values");
        int rank = (int) Math.ceil(p / 100.0 * a.length);
        return a[Math.max(0, Math.min(a.length - 1, rank - 1))];
    }

    /**
     * Robust scale of a baseline: MAD, or when more than half the values are identical (MAD zero) 1.253314 x the mean absolute deviation about the median.
     * Returns the scale in the units of {@code score}: score = 0.6745 |x - median| / MAD for the MAD form and |x - median| / s otherwise. Zero means the
     * baseline has no variation, so no deviation scale exists.
     */
    public record Scale(double median, double mad, double fallback) {
        public boolean defined() {
            return mad > 0 || fallback > 0;
        }

        public double score(double x) {
            double d = Math.abs(x - median);
            return mad > 0 ? 0.6745 * d / mad : d / fallback;
        }

        public String form() {
            return mad > 0 ? "MAD" : "MEAN_ABSOLUTE_DEVIATION";
        }
    }

    public static Scale scale(List<Double> baseline) {
        double med = median(baseline);
        double mad = mad(baseline);
        double fallback = mad > 0 ? 0.0 : 1.253314 * meanAbsoluteDeviation(baseline);
        return new Scale(med, mad, fallback);
    }
}
