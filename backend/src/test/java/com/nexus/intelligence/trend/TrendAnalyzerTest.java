package com.nexus.intelligence.trend;

import com.nexus.intelligence.anomaly.CleanedSeries;
import com.nexus.intelligence.anomaly.RawRow;
import com.nexus.intelligence.anomaly.Sample;
import com.nexus.intelligence.anomaly.SeriesCleaner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6.8 maths on pure inputs: no database, no clock, no model. The motor example (98, 99, 100) is the mandatory case; every statistical claim is tested at both
 * sides of its documented threshold.
 */
class TrendAnalyzerTest {
    private static final TrendSettings S = TrendSettings.defaults();
    private static final Instant AT = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant T0 = Instant.parse("2026-10-20T08:00:00Z");

    private static List<Sample> series(double[] values, long stepSeconds) {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) out.add(new Sample("r" + i, T0.plusSeconds(i * stepSeconds), values[i]));
        return out;
    }

    private static TrendResult timestamped(double[] values, long stepSeconds, HardLimit limit) {
        return TrendAnalyzer.analyse(series(values, stepSeconds), "CELSIUS", T0, AT, null, limit, null, S, AT);
    }

    private static TrendResult withResolution(double[] values, long stepSeconds, double resolution) {
        return TrendAnalyzer.analyse(series(values, stepSeconds), "CELSIUS", T0, AT, null, null, resolution, S, AT);
    }

    // ------------------------------------------------------------------ the mandatory motor example

    @Test
    void p6_131_motorReadings98_99_100AreIncreasingButNoRateIsCalculatedWithoutTimestamps() {
        TrendResult r = TrendAnalyzer.analyseValues(List.of(98.0, 99.0, 100.0), "CELSIUS", null, null, S, AT);
        assertThat(r.direction()).isEqualTo(TrendDirection.INCREASING);
        assertThat(r.basis()).isEqualTo("VALUES_ONLY");
        assertThat(r.rate()).as("no rate per second or minute without timestamps").isNull();
        assertThat(r.spanSeconds()).isNull();
        assertThat(r.observedChange()).isEqualTo(2.0);
        assertThat(r.established().established()).isFalse();
        assertThat(r.established().reasons()).contains("NO_TIMESTAMPS", "INSUFFICIENT_SAMPLES");
        assertThat(r.limit()).as("no limit configured: nothing is called dangerous").isNull();
        assertThat(String.join(" ", r.notes())).contains("No limit was configured, so this analysis makes no statement about safety");
        assertThat(String.join(" ", r.notes()).toLowerCase()).doesNotContain("danger").doesNotContain("unsafe");
        assertThat(String.join(" ", r.notes())).contains("Acceleration and trend strength are not reported");
    }

    @Test
    void p6_132_aConfiguredLimitOf100InTheSameUnitIsMetBy100WithGreaterOrEqualAndNotBy99() {
        List<Double> motor = List.of(98.0, 99.0, 100.0);
        TrendResult met = TrendAnalyzer.analyseValues(motor, "CELSIUS", new HardLimit(100, "CELSIUS", HardLimit.Comparison.GREATER_OR_EQUAL), null, S, AT);
        assertThat(met.limit().status()).isEqualTo("MET");
        assertThat(met.limit().margin()).isEqualTo(0.0);
        TrendResult strict = TrendAnalyzer.analyseValues(motor, "CELSIUS", new HardLimit(100, "CELSIUS", HardLimit.Comparison.GREATER_THAN), null, S, AT);
        assertThat(strict.limit().status()).isEqualTo("NOT_MET");
        TrendResult below = TrendAnalyzer.analyseValues(List.of(97.0, 98.0, 99.0), "CELSIUS", new HardLimit(100, "CELSIUS", HardLimit.Comparison.GREATER_OR_EQUAL), null, S, AT);
        assertThat(below.limit().status()).isEqualTo("NOT_MET");
        assertThat(below.limit().margin()).isEqualTo(1.0);
        TrendResult mismatch = TrendAnalyzer.analyseValues(motor, "FAHRENHEIT", new HardLimit(100, "CELSIUS", HardLimit.Comparison.GREATER_OR_EQUAL), null, S, AT);
        assertThat(mismatch.limit().status()).as("a unit mismatch makes no comparison").isEqualTo("UNIT_MISMATCH");
        assertThat(mismatch.limit().margin()).isNull();
        assertThat(String.join(" ", met.limits())).contains("not a certified hardware emergency cutoff");
        assertThatThrownBy(() -> new HardLimit(Double.NaN, "CELSIUS", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HardLimit(100, " ", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void p6_131_theSameReadingsWithMinuteTimestampsGiveARatePerMinuteButStillNoEstablishedTrend() {
        TrendResult r = timestamped(new double[] {98, 99, 100}, 60, null);
        assertThat(r.direction()).isEqualTo(TrendDirection.INCREASING);
        assertThat(r.rate()).isNotNull();
        assertThat(r.rate().perMinute()).isEqualTo(1.0);
        assertThat(r.rate().unit()).isEqualTo("CELSIUS/min");
        assertThat(r.rate().method()).isEqualTo("THEIL_SEN_MEDIAN_OF_PAIRWISE_SLOPES");
        assertThat(r.rate().established()).isFalse();
        assertThat(r.established().reasons()).contains("INSUFFICIENT_SAMPLES", "SPAN_TOO_SHORT");
        assertThat(r.sampleCount()).isEqualTo(3);
        assertThat(r.spanSeconds()).isEqualTo(120.0);
    }

    // ------------------------------------------------------------------ evidence thresholds

    @Test
    void p6_135_fewerThanThreeReadingsAreInsufficientEvidence() {
        TrendResult two = TrendAnalyzer.analyseValues(List.of(98.0, 99.0), "CELSIUS", null, null, S, AT);
        assertThat(two.direction()).isEqualTo(TrendDirection.INSUFFICIENT_EVIDENCE);
        assertThat(two.reason()).contains("at least 3");
        assertThat(two.rate()).isNull();
        assertThat(TrendAnalyzer.analyseValues(List.of(), "CELSIUS", null, null, S, AT).direction()).isEqualTo(TrendDirection.INSUFFICIENT_EVIDENCE);
        assertThat(TrendAnalyzer.analyseValues(List.of(98.0, 99.0, 100.0), "CELSIUS", null, null, S, AT).direction()).as("exactly the minimum is enough for a direction").isEqualTo(TrendDirection.INCREASING);
    }

    @Test
    void p6_133_p6_134_aGradualRegularIncreaseIsEstablishedWithARateAndNoTrendStrengthIsClaimed() {
        Random noise = new Random(7);
        double[] v = new double[30];
        for (int i = 0; i < v.length; i++) v[i] = 40 + 0.5 * i + noise.nextGaussian() * 0.1;
        TrendResult r = timestamped(v, 60, null);
        assertThat(r.direction()).isEqualTo(TrendDirection.INCREASING);
        assertThat(r.shape()).isEqualTo("GRADUAL");
        assertThat(r.established().established()).as(String.valueOf(r.established().reasons())).isTrue();
        assertThat(r.established().mannKendallZ()).isGreaterThan(1.96);
        assertThat(r.rate().perMinute()).isBetween(0.45, 0.55);
        assertThat(r.rate().established()).isTrue();
        assertThat(String.join(" ", r.notes())).contains("Acceleration and trend strength are not reported");
        double[] down = new double[30];
        for (int i = 0; i < down.length; i++) down[i] = 80 - 0.4 * i + noise.nextGaussian() * 0.1;
        TrendResult d = timestamped(down, 60, null);
        assertThat(d.direction()).isEqualTo(TrendDirection.DECREASING);
        assertThat(d.established().established()).isTrue();
        assertThat(d.rate().perMinute()).isBetween(-0.45, -0.35);
    }

    @Test
    void p6_138_stableReadingsWithNoiseAreStableAndNeverATrend() {
        for (long seed = 1; seed <= 25; seed++) {
            Random noise = new Random(seed);
            double[] v = new double[40];
            for (int i = 0; i < v.length; i++) v[i] = 21.0 + noise.nextGaussian() * 0.2;
            TrendResult r = timestamped(v, 60, null);
            assertThat(r.direction()).as("seed " + seed).isEqualTo(TrendDirection.STABLE);
            assertThat(r.established().established()).isFalse();
        }
        TrendResult flat = timestamped(new double[] {5, 5, 5, 5, 5, 5}, 60, null);
        assertThat(flat.direction()).isEqualTo(TrendDirection.STABLE);
        assertThat(flat.resolution()).isEqualTo(0.0);
        assertThat(flat.rate().perMinute()).isEqualTo(0.0);
    }

    @Test
    void p6_139_aSuddenStepIsLabelledAStepAndIsNotAnEstablishedGradualTrend() {
        double[] v = new double[20];
        for (int i = 0; i < v.length; i++) v[i] = i < 10 ? 20 : 30;
        TrendResult r = withResolution(v, 60, 0.1);   // a two-level series cannot reveal the resolution, so it is configured
        assertThat(r.direction()).isEqualTo(TrendDirection.INCREASING);
        assertThat(r.shape()).isEqualTo("SUDDEN_STEP");
        assertThat(r.established().established()).isFalse();
        assertThat(r.established().reasons()).contains("STEP_NOT_A_GRADUAL_TREND");
        // a step in the last reading is also found even though the fitted slope is flat
        double[] late = new double[12];
        for (int i = 0; i < late.length; i++) late[i] = i < 11 ? 20 : 35;
        TrendResult last = withResolution(late, 60, 0.1);
        assertThat(last.direction()).isEqualTo(TrendDirection.INCREASING);
        assertThat(last.shape()).isEqualTo("SUDDEN_STEP");
        // a spike that returns is not a net change
        double[] spike = new double[12];
        for (int i = 0; i < spike.length; i++) spike[i] = i == 6 ? 35 : 20;
        assertThat(withResolution(spike, 60, 0.1).direction()).isEqualTo(TrendDirection.STABLE);
        // without a configured resolution the same two-level step is one quantum of the sensor: not a trend, and the note says why
        TrendResult unknown = timestamped(v, 60, null);
        assertThat(unknown.direction()).isEqualTo(TrendDirection.STABLE);
        assertThat(String.join(" ", unknown.notes())).contains("resolution cannot be estimated");
    }

    @Test
    void p6_141_aQuantisedSensorsOneStepChangeIsNotATrend() {
        assertThat(timestamped(new double[] {20, 20, 21}, 60, null).direction()).isEqualTo(TrendDirection.STABLE);
        assertThat(timestamped(new double[] {20, 20, 20, 21, 21, 21, 21}, 60, null).direction()).isEqualTo(TrendDirection.STABLE);
        TrendResult stairs = timestamped(new double[] {20, 20, 21, 21, 22, 22, 23, 23, 24, 24, 25, 25}, 60, null);
        assertThat(stairs.direction()).isEqualTo(TrendDirection.INCREASING);
        assertThat(stairs.resolution()).isEqualTo(1.0);
        TrendResult configured = TrendAnalyzer.analyse(series(new double[] {20, 20, 21, 21, 21, 22}, 60), "CELSIUS", T0, AT, null, null, 5.0, S, AT);
        assertThat(configured.resolution()).as("a configured coarser resolution raises the bar").isEqualTo(5.0);
        assertThat(configured.direction()).isEqualTo(TrendDirection.STABLE);
    }

    @Test
    void p6_136_p6_137_irregularSamplingAndGapsPreventAnEstablishedTrendButKeepTheDescription() {
        double[] v = new double[14];
        for (int i = 0; i < v.length; i++) v[i] = 30 + i;
        long[] seconds = {0, 10, 300, 320, 900, 905, 1500, 1520, 2200, 2210, 3000, 3030, 3500, 3600};
        List<Sample> irregular = new ArrayList<>();
        for (int i = 0; i < v.length; i++) irregular.add(new Sample("r" + i, T0.plusSeconds(seconds[i]), v[i]));
        TrendResult r = TrendAnalyzer.analyse(irregular, "CELSIUS", T0, AT, null, null, null, S, AT);
        assertThat(r.direction()).isEqualTo(TrendDirection.INCREASING);
        assertThat(r.established().established()).isFalse();
        assertThat(r.established().reasons()).contains("IRREGULAR_SAMPLING");
        assertThat(r.quality()).containsEntry("irregularSampling", true);

        double[] w = new double[14];
        List<Sample> gap = new ArrayList<>();
        for (int i = 0; i < w.length; i++) gap.add(new Sample("g" + i, T0.plusSeconds(i < 7 ? i * 120L : 840 + 120L * 20 + (i - 7) * 120L), 30 + i));
        TrendResult g = TrendAnalyzer.analyse(gap, "CELSIUS", T0, AT, null, null, null, S, AT);
        assertThat(g.established().reasons()).contains("GAPS_PRESENT");
        assertThat((Integer) g.quality().get("gapsPresent")).isGreaterThanOrEqualTo(1);
        assertThat(g.established().established()).isFalse();
    }

    @Test
    void p6_136_p6_140_theResultDoesNotDependOnTheOrderOfDeliveryAndDuplicatesAndFutureReadingsAreDealtWithDeterministically() {
        List<RawRow> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) rows.add(new RawRow("id" + i, T0.plusSeconds(i * 60L), "NUMBER", 30.0 + i, "CELSIUS"));
        rows.add(new RawRow("dup", T0.plusSeconds(60), "NUMBER", 31.0, "CELSIUS"));                          // identical duplicate: collapsed
        rows.add(new RawRow("conflict-a", T0.plusSeconds(120), "NUMBER", 99.0, "CELSIUS"));                   // same time, different value as id2: the instant is removed
        rows.add(new RawRow("future", AT.plusSeconds(7200), "NUMBER", 500.0, "CELSIUS"));                     // beyond the skew tolerance: excluded
        CleanedSeries a = SeriesCleaner.clean(rows, AT, S.skewToleranceSeconds(), false);
        List<RawRow> shuffled = new ArrayList<>(rows);
        Collections.shuffle(shuffled, new Random(3));
        CleanedSeries b = SeriesCleaner.clean(shuffled, AT, S.skewToleranceSeconds(), false);
        assertThat(a.samples()).isEqualTo(b.samples());
        assertThat(a.quality().futureDated()).isEqualTo(1);
        assertThat(a.quality().duplicatesCollapsed()).isEqualTo(1);
        assertThat(a.quality().conflictingTimestamps()).isEqualTo(1);
        TrendResult r1 = TrendAnalyzer.analyse(a.samples(), a.unit(), T0, AT, a.quality(), null, null, S, AT);
        TrendResult r2 = TrendAnalyzer.analyse(b.samples(), b.unit(), T0, AT, b.quality(), null, null, S, AT);
        assertThat(r1).isEqualTo(r2);
        assertThat(String.join(" ", r1.notes())).contains("1 reading(s) stamped in the future were excluded");
        assertThat(r1.lastValue()).isNotEqualTo(500.0);
    }

    @Test
    void p6_134_theAnalysisIsBoundedAndDeterministicOnALongSeries() {
        double[] v = new double[5000];
        for (int i = 0; i < v.length; i++) v[i] = 20 + 0.01 * i;
        long started = System.nanoTime();
        TrendResult r = timestamped(v, 10, null);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(5));
        assertThat(r.established().pointsUsed()).isEqualTo(200);
        assertThat(r.sampleCount()).isEqualTo(5000);
        assertThat(r.direction()).isEqualTo(TrendDirection.INCREASING);
        assertThat(timestamped(v, 10, null)).isEqualTo(r);
    }

    @Test
    void p6_142_aNonNumericCapabilityIsNotApplicableAndTheSettingsAreValidated() {
        TrendResult na = TrendAnalyzer.notApplicable("the capability is not a continuous numeric reading", null, T0, AT, java.util.Map.of(), AT);
        assertThat(na.direction()).isEqualTo(TrendDirection.NOT_APPLICABLE);
        assertThat(na.rate()).isNull();
        assertThat(na.limit()).isNull();
        assertThatThrownBy(() -> new TrendSettings(6, 2, 10, 600, 1.96, 0.5, 5, 200, 2, 300, 5000)).hasMessageContaining("min-descriptive-samples");
        assertThatThrownBy(() -> new TrendSettings(6, 3, 5, 600, 1.96, 0.5, 5, 200, 2, 300, 5000)).hasMessageContaining("min-established-samples");
        assertThatThrownBy(() -> new TrendSettings(6, 3, 10, 600, 0.5, 0.5, 5, 200, 2, 300, 5000)).hasMessageContaining("significance-z");
        assertThatThrownBy(() -> TrendAnalyzer.analyseValues(List.of(1.0, Double.NaN, 3.0), "C", null, null, S, AT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void p6_133_theSignificanceThresholdDecidesWhetherARegularTrendIsEstablished() {
        double[] linear = new double[12];
        for (int i = 0; i < linear.length; i++) linear[i] = 30 + i;
        TrendResult normal = TrendAnalyzer.analyse(series(linear, 60), "CELSIUS", T0, AT, null, null, null, S, AT);
        assertThat(normal.established().established()).as(String.valueOf(normal.established().reasons())).isTrue();
        assertThat(normal.established().mannKendallZ()).isBetween(4.0, 5.0);
        TrendSettings strict = new TrendSettings(6, 3, 10, 600, 5.0, 0.5, 5, 200, 2, 300, 5000);
        TrendResult tight = TrendAnalyzer.analyse(series(linear, 60), "CELSIUS", T0, AT, null, null, null, strict, AT);
        assertThat(tight.direction()).isEqualTo(TrendDirection.INCREASING);
        assertThat(tight.established().established()).isFalse();
        assertThat(tight.established().reasons()).containsExactly("NOT_SIGNIFICANT");
    }

    @Test
    void p6_139_aChangeSpreadOverSeveralStepsIsGradualNotASuddenStep() {
        double[] v = new double[18];
        for (int i = 0; i < v.length; i++) v[i] = i < 6 ? 20 : i < 12 ? 25 : 30;
        TrendResult r = withResolution(v, 60, 0.1);
        assertThat(r.direction()).isEqualTo(TrendDirection.INCREASING);
        assertThat(r.shape()).isEqualTo("GRADUAL");
    }
}
