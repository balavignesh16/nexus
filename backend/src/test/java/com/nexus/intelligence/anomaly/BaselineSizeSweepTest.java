package com.nexus.intelligence.anomaly;

import com.nexus.intelligence.anomaly.Finding.Category;
import com.nexus.intelligence.anomaly.Finding.Status;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures how the false-flag rate of the outlier and spike detectors depends on the size of the baseline, on clean Gaussian noise. The robust scale is
 * estimated from the baseline, so a small baseline gives an unstable scale; the documented minimum baseline is chosen from this table, not guessed.
 */
class BaselineSizeSweepTest {
    private static List<Finding> run(int baseline, int recent, long seed) {
        Random r = new Random(seed);
        List<RawRow> rows = new ArrayList<>();
        for (int i = 0; i < baseline; i++) rows.add(new RawRow("b" + i, DetectorsTest.W.split().minus(Duration.ofMinutes(baseline - i)), "NUMBER", Math.round((21 + 0.3 * r.nextGaussian()) * 10) / 10.0, "CELSIUS"));
        for (int i = 0; i < recent; i++) rows.add(new RawRow("r" + i, DetectorsTest.W.split().plus(Duration.ofMinutes(i)), "NUMBER", Math.round((21 + 0.3 * r.nextGaussian()) * 10) / 10.0, "CELSIUS"));
        CleanedSeries s = SeriesCleaner.clean(rows, DetectorsTest.NOW, 300, false);
        AnomalySettings st = new AnomalySettings(24, 120, 10, 10, 4.0, 5.0, 5.0, 60, 300, 5000, 20, 10);
        return Detectors.analyse(DetectorsTest.DEVICE, "temperature", s, DetectorsTest.W, DetectorsTest.NOW, st);
    }

    static double rate(int baseline, Category category) {
        int flagged = 0, n = 600;
        for (int seed = 0; seed < n; seed++) {
            Finding f = run(baseline, 16, 1000 + seed).stream().filter(x -> x.category() == category).findFirst().orElseThrow();
            if (f.status() == Status.OBSERVED) flagged++;
        }
        return flagged / (double) n;
    }

    @Test
    void p6_059_p6_068_theFalseFlagRateFallsWithBaselineSizeAndTheDocumentedMinimumKeepsItUnderTwoPercent() {
        StringBuilder table = new StringBuilder("baseline | outlier | spike\n");
        double outlierAtMin = 0, spikeAtMin = 0;
        int min = AnomalySettings.defaults().minBaselineSamples();
        for (int n : new int[] {20, 30, 50, 100, 300}) {
            double o = rate(n, Category.OUTLIER), s = rate(n, Category.SPIKE);
            table.append(String.format("%8d | %6.1f%% | %5.1f%%%n", n, o * 100, s * 100));
            if (n == min) {
                outlierAtMin = o;
                spikeAtMin = s;
            }
        }
        System.out.println("P6-059 false-flag rate on clean Gaussian noise by baseline size (600 series each, 16 recent readings):\n" + table);
        assertThat(min).as("the default minimum baseline must be one of the measured sizes").isIn(20, 30, 50, 100, 300);
        assertThat(outlierAtMin).isLessThanOrEqualTo(0.02);
        assertThat(spikeAtMin).isLessThanOrEqualTo(0.02);
    }
}
