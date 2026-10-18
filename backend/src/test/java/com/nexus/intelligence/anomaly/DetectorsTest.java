package com.nexus.intelligence.anomaly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.intelligence.anomaly.Finding.Category;
import com.nexus.intelligence.anomaly.Finding.Status;
import com.nexus.intelligence.anomaly.Finding.Window;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.IntToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-057..P6-060, P6-067, P6-068: the four detectors on synthetic series with a known truth. Each positive has a matching negative; the evidence thresholds are
 * exercised at their edges; the false-positive rate on clean noise is measured, not assumed.
 */
class DetectorsTest {
    static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    static final UUID DEVICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final AnomalySettings S = AnomalySettings.defaults();
    static final Window W = new Window(NOW.minus(Duration.ofHours(24)), NOW.minus(Duration.ofMinutes(120)), NOW);

    /** One reading per minute from the window start to now: value(i) per index, rounded to one decimal like a real sensor. */
    static List<RawRow> minuteSeries(IntToDoubleFunction value) {
        List<RawRow> rows = new ArrayList<>();
        int n = 24 * 60;
        for (int i = 0; i < n; i++) rows.add(new RawRow("r" + i, W.from().plusSeconds(60L * i), "NUMBER", Math.round(value.applyAsDouble(i) * 10.0) / 10.0, "CELSIUS"));
        return rows;
    }

    static IntToDoubleFunction noise(long seed, double mean, double sd) {
        Random r = new Random(seed);
        double[] v = new double[24 * 60];
        for (int i = 0; i < v.length; i++) v[i] = mean + sd * r.nextGaussian();
        return i -> v[i];
    }

    static List<Finding> run(List<RawRow> rows) {
        CleanedSeries series = SeriesCleaner.clean(rows, NOW, S.skewToleranceSeconds(), false);
        return Detectors.analyse(DEVICE, "temperature", series, W, NOW, S);
    }

    static Finding of(List<Finding> all, Category c) {
        return all.stream().filter(f -> f.category() == c).findFirst().orElseThrow();
    }

    static int recentStart() {
        return 24 * 60 - 120;
    }

    @Test
    void p6_058_aSensorThatRepeatsOneValueAfterVaryingIsObservedAsFlat() {
        IntToDoubleFunction base = noise(1, 21.0, 0.3);
        List<Finding> f = run(minuteSeries(i -> i >= recentStart() ? 21.3 : base.applyAsDouble(i)));
        Finding flat = of(f, Category.FLATLINE);
        assertThat(flat.status()).isEqualTo(Status.OBSERVED);
        assertThat(flat.reason()).isEqualTo("IDENTICAL_RECENT_READINGS");
        assertThat(flat.statistics().get("recentMin")).isEqualTo(21.3);
        assertThat(flat.measurements()).hasSize(2);
        assertThat(flat.known().getFirst()).contains("120 persisted readings");
        assertThat(flat.confirmedFault()).isFalse();
        assertThat(flat.uncertain().toString()).contains("does not show a hardware fault");
    }

    @Test
    void p6_058_aNoisyRecentWindowIsNotFlatAndANaturallyConstantSeriesIsNotJudged() {
        assertThat(of(run(minuteSeries(noise(2, 21.0, 0.3))), Category.FLATLINE).status()).isEqualTo(Status.NOT_OBSERVED);
        // a setpoint-like quantity that never varied: flat is its normal state, so there is no evidence either way
        Finding constant = of(run(minuteSeries(i -> 22.0)), Category.FLATLINE);
        assertThat(constant.status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
        assertThat(constant.reason()).isEqualTo("BASELINE_NOT_VARYING");
    }

    @Test
    void p6_059_aReadingFarFromTheBaselineIsAnOutlierAndOrdinaryNoiseIsNot() {
        IntToDoubleFunction base = noise(3, 21.0, 0.3);
        List<Finding> f = run(minuteSeries(i -> i == recentStart() + 40 ? 27.0 : base.applyAsDouble(i)));
        Finding o = of(f, Category.OUTLIER);
        assertThat(o.status()).isEqualTo(Status.OBSERVED);
        assertThat(o.measurements()).hasSize(1);
        assertThat(o.measurements().getFirst().get("value")).isEqualTo(27.0);
        assertThat(o.measurements().getFirst().get("rowId")).isEqualTo("r" + (recentStart() + 40));
        assertThat(o.evidenceRefs()).contains("telemetry-row:r" + (recentStart() + 40));
        assertThat(of(run(minuteSeries(base)), Category.OUTLIER).status()).isEqualTo(Status.NOT_OBSERVED);
    }

    @Test
    void p6_059_anAbruptChangeBetweenConsecutiveReadingsIsASpike() {
        IntToDoubleFunction base = noise(4, 21.0, 0.3);
        Finding s = of(run(minuteSeries(i -> i >= recentStart() + 60 ? base.applyAsDouble(i) + 6.0 : base.applyAsDouble(i))), Category.SPIKE);
        assertThat(s.status()).isEqualTo(Status.OBSERVED);
        assertThat(s.measurements().getFirst().get("rowId")).isEqualTo("r" + (recentStart() + 60));
        assertThat((Double) s.measurements().getFirst().get("change")).isGreaterThan(4.0);
        assertThat(of(run(minuteSeries(base)), Category.SPIKE).status()).isEqualTo(Status.NOT_OBSERVED);
    }

    @Test
    void p6_059_p6_067_cleanGaussianNoiseIsFlaggedByNoDetectorInMoreThanTheStatedBound() {
        int series = 400, flagged = 0;
        for (int seed = 100; seed < 100 + series; seed++) {
            List<Finding> f = run(minuteSeries(noise(seed, 21.0, 0.3)));
            if (f.stream().anyMatch(x -> x.status() == Status.OBSERVED)) {
                flagged++;
                f.stream().filter(x -> x.status() == Status.OBSERVED).forEach(x -> System.out.println("  false flag seed " + x.reason() + " " + x.category()));
            }
        }
        double rate = flagged / (double) series;
        System.out.println("P6-059 measured false-positive rate on clean Gaussian noise: " + flagged + "/" + series + " = " + rate);
        assertThat(rate).as("documented bound for clean Gaussian noise").isLessThanOrEqualTo(0.02);
    }

    @Test
    void p6_060_silenceBeyondTheDevicesOwnCadenceIsObservedWithoutClaimingTheDeviceIsOffline() {
        IntToDoubleFunction base = noise(5, 21.0, 0.3);
        // the device stops reporting 40 minutes before the evaluation
        List<RawRow> rows = minuteSeries(base).stream().filter(r -> r.at().isBefore(NOW.minus(Duration.ofMinutes(40)))).toList();
        Finding g = of(run(rows), Category.GAP);
        assertThat(g.status()).isEqualTo(Status.OBSERVED);
        assertThat(g.reason()).isEqualTo("NO_RECENT_READING");
        assertThat((Double) g.statistics().get("secondsSinceLastReading")).isBetween(2400.0, 2460.0);
        assertThat(String.join(" ", g.uncertain())).contains("do not show that the device is offline");
        // an interior silence of 15 minutes
        List<RawRow> hole = minuteSeries(base).stream().filter(r -> r.at().isBefore(NOW.minus(Duration.ofMinutes(60))) || r.at().isAfter(NOW.minus(Duration.ofMinutes(45)))).toList();
        Finding interior = of(run(hole), Category.GAP);
        assertThat(interior.status()).isEqualTo(Status.OBSERVED);
        assertThat(interior.reason()).isEqualTo("SILENCE_BETWEEN_READINGS");
        // continuous reporting
        assertThat(of(run(minuteSeries(base)), Category.GAP).status()).isEqualTo(Status.NOT_OBSERVED);
        // a slow sensor (one reading per 30 minutes) is judged against its own cadence: 40 minutes of silence is normal for it
        List<RawRow> slow = new ArrayList<>();
        for (int i = 0; i < 48; i++) slow.add(new RawRow("s" + i, W.from().plus(Duration.ofMinutes(30L * i)), "NUMBER", 21.0 + (i % 5) * 0.1, "CELSIUS"));
        assertThat(of(run(slow), Category.GAP).status()).as("slow cadence is its own normal").isNotEqualTo(Status.OBSERVED);
    }

    @Test
    void p6_060_anIrregularReporterHasNoDefinedSilence() {
        Random r = new Random(9);
        List<RawRow> rows = new ArrayList<>();
        Instant t = W.from();
        int i = 0;
        while (t.isBefore(NOW.minus(Duration.ofMinutes(30)))) {
            rows.add(new RawRow("x" + i++, t, "NUMBER", 21.0 + r.nextGaussian() * 0.3, "CELSIUS"));
            t = t.plusSeconds(10 + (long) (r.nextDouble() * r.nextDouble() * 4000)); // bursts and long pauses
        }
        Finding g = of(run(rows), Category.GAP);
        assertThat(g.status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
        assertThat(g.reason()).isEqualTo("IRREGULAR_CADENCE");
    }

    @Test
    void p6_068_tooLittleEvidenceIsInsufficientEvidenceForEveryDetector() {
        // 15 readings only
        List<RawRow> few = new ArrayList<>();
        for (int i = 0; i < 15; i++) few.add(new RawRow("f" + i, NOW.minus(Duration.ofMinutes(15 - i)), "NUMBER", 20.0 + i * 0.1, "CELSIUS"));
        for (Finding f : run(few)) {
            assertThat(f.status()).as(f.category().name()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
            assertThat(f.reason()).isIn("BASELINE_TOO_SMALL", "RECENT_TOO_SMALL");
            assertThat(f.measurements()).isEmpty();
        }
        // no readings at all
        for (Finding f : run(List.of())) assertThat(f.status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
        // exactly at the edge: 99 baseline readings is not enough, 100 is (the minimum was chosen from the measured false-flag table)
        assertThat(of(run(baselineOf(99)), Category.OUTLIER).status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
        assertThat(of(run(baselineOf(100)), Category.OUTLIER).status()).isNotEqualTo(Status.INSUFFICIENT_EVIDENCE);
    }

    private static List<RawRow> baselineOf(int n) {
        Random r = new Random(11);
        List<RawRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) rows.add(new RawRow("b" + i, W.split().minus(Duration.ofMinutes(n - i)), "NUMBER", Math.round((21 + r.nextGaussian() * 0.3) * 10) / 10.0, "CELSIUS"));
        for (int i = 0; i < 30; i++) rows.add(new RawRow("c" + i, W.split().plus(Duration.ofMinutes(i)), "NUMBER", Math.round((21 + r.nextGaussian() * 0.3) * 10) / 10.0, "CELSIUS"));
        return rows;
    }

    @Test
    void p6_058_booleanAndOtherNonNumericCapabilitiesAreNeverAnalysedAsContinuous() {
        List<RawRow> relay = new ArrayList<>();
        for (int i = 0; i < 500; i++) relay.add(new RawRow("b" + i, W.from().plusSeconds(60L * i), "BOOLEAN", null, null));
        for (Finding f : run(relay)) {
            assertThat(f.status()).isEqualTo(Status.NOT_APPLICABLE);
            assertThat(f.reason()).isEqualTo("NOT_A_CONTINUOUS_NUMERIC_CAPABILITY");
        }
        // a boolean stored with a numeric shadow value is still a BOOLEAN capability
        List<RawRow> shadow = new ArrayList<>();
        for (int i = 0; i < 500; i++) shadow.add(new RawRow("c" + i, W.from().plusSeconds(60L * i), "BOOLEAN", i % 2 == 0 ? 1.0 : 0.0, null));
        assertThat(run(shadow)).allMatch(f -> f.status() == Status.NOT_APPLICABLE);
        List<RawRow> mixed = new ArrayList<>(minuteSeries(noise(6, 21, 0.3)));
        mixed.add(new RawRow("m", NOW.minusSeconds(30), "BOOLEAN", null, null));
        assertThat(run(mixed)).allMatch(f -> f.reason().equals("MIXED_VALUE_TYPES"));
        List<RawRow> units = new ArrayList<>(minuteSeries(noise(7, 21, 0.3)));
        units.add(new RawRow("u", NOW.minusSeconds(30), "NUMBER", 70.0, "FAHRENHEIT"));
        assertThat(run(units)).allMatch(f -> f.reason().equals("MIXED_UNITS") && f.status() == Status.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void p6_057_duplicatesAndOutOfOrderDeliveryDoNotChangeTheFindings() {
        IntToDoubleFunction base = noise(8, 21.0, 0.3);
        List<RawRow> clean = minuteSeries(i -> i == recentStart() + 10 ? 27.0 : base.applyAsDouble(i));
        List<Finding> reference = run(clean);
        List<RawRow> shuffled = new ArrayList<>(clean);
        Collections.shuffle(shuffled, new Random(42));
        List<RawRow> duplicated = new ArrayList<>(shuffled);
        for (int i = 0; i < 50; i++) duplicated.add(new RawRow("dup" + i, clean.get(i * 10).at(), "NUMBER", clean.get(i * 10).numeric(), "CELSIUS"));
        List<Finding> again = run(duplicated);
        assertThat(again.stream().map(Finding::findingId).toList()).isEqualTo(reference.stream().map(Finding::findingId).toList());
        assertThat(again.stream().map(Finding::status).toList()).isEqualTo(reference.stream().map(Finding::status).toList());
        assertThat(again.getFirst().quality().get("duplicatesCollapsed")).isEqualTo(50);
        assertThat(again.getFirst().quality().get("kept")).isEqualTo(clean.size());
    }

    @Test
    void p6_057_twoDifferentValuesAtOneTimestampAreRemovedNotGuessed() {
        List<RawRow> rows = new ArrayList<>(minuteSeries(noise(12, 21.0, 0.3)));
        Instant t = rows.get(100).at();
        rows.add(new RawRow("x", t, "NUMBER", rows.get(100).numeric() + 3.0, "CELSIUS"));
        List<Finding> f = run(rows);
        assertThat(f.getFirst().quality().get("conflictingTimestampsRemoved")).isEqualTo(1);
        assertThat(f.getFirst().quality().get("kept")).isEqualTo(rows.size() - 2);
    }

    @Test
    void p6_057_deviceClockSkewIsBoundedAndWindowBoundariesAreHalfOpen() {
        IntToDoubleFunction base = noise(13, 21.0, 0.3);
        List<RawRow> rows = new ArrayList<>(minuteSeries(base));
        rows.add(new RawRow("future", NOW.plusSeconds(10 * 60), "NUMBER", 90.0, "CELSIUS"));      // 10 min ahead of the backend: beyond the 300 s tolerance
        rows.add(new RawRow("nearfuture", NOW.plusSeconds(120), "NUMBER", 21.0, "CELSIUS"));       // within tolerance: kept in the series ...
        List<Finding> f = run(rows);
        assertThat(f.getFirst().quality().get("futureDatedExcluded")).isEqualTo(1);
        assertThat(of(f, Category.OUTLIER).status()).isEqualTo(Status.NOT_OBSERVED); // the 90.0 reading never reached the detector
        assertThat(of(f, Category.OUTLIER).samples().get("recent")).isEqualTo(120);   // ... but outside [split, to) so it is not counted in the recent window
        // a reading exactly at `split` is recent, exactly at `to` is not in the window at all
        List<RawRow> edge = new ArrayList<>(minuteSeries(i -> i == recentStart() ? 40.0 : base.applyAsDouble(i)));
        Finding atSplit = of(run(edge), Category.OUTLIER);
        assertThat(atSplit.status()).as("the reading stamped exactly at the split instant belongs to the recent window").isEqualTo(Status.OBSERVED);
        List<RawRow> atTo = new ArrayList<>(minuteSeries(base));
        atTo.add(new RawRow("atTo", NOW, "NUMBER", 40.0, "CELSIUS"));
        assertThat(of(run(atTo), Category.OUTLIER).status()).as("a reading at the exclusive upper bound is outside the window").isEqualTo(Status.NOT_OBSERVED);
    }

    @Test
    void p6_057_theSameEvidenceGivesTheSameFindingsAndAChangedValueGivesANewIdentity() throws Exception {
        IntToDoubleFunction base = noise(14, 21.0, 0.3);
        List<Finding> a = run(minuteSeries(base)), b = run(minuteSeries(base));
        assertThat(a).isEqualTo(b);
        List<Finding> changed = run(minuteSeries(i -> i == 10 ? base.applyAsDouble(i) + 0.1 : base.applyAsDouble(i)));
        assertThat(changed.getFirst().findingId()).isNotEqualTo(a.getFirst().findingId());
        // no probability, confidence or fault claim anywhere in the serialised output
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var tree = mapper.valueToTree(run(minuteSeries(i -> i >= recentStart() ? 21.3 : base.applyAsDouble(i))));
        List<String> fieldNames = new ArrayList<>();
        tree.findParents("findingId").forEach(n -> n.fieldNames().forEachRemaining(fieldNames::add));
        tree.findValues("statistics").forEach(n -> n.fieldNames().forEachRemaining(fieldNames::add));
        assertThat(fieldNames).isNotEmpty();
        assertThat(fieldNames.stream().map(String::toLowerCase)).noneMatch(n -> n.contains("probab") || n.contains("confidence") || n.contains("likelihood") || n.contains("percent"));
        assertThat(tree.findValues("confirmedFault")).allMatch(n -> !n.asBoolean());
    }

    @Test
    void p6_058_theReadingStepIsTheSeriesOwnSoACoarseSensorIsJudgedByItsOwnPrecision() {
        // a sensor that reports whole degrees: five equal readings are normal for it, a flat hour is not
        IntToDoubleFunction coarse = i -> 20 + Math.round(Math.sin(i / 90.0) * 3);
        List<RawRow> rows = new ArrayList<>();
        for (int i = 0; i < 24 * 60; i++) rows.add(new RawRow("w" + i, W.from().plusSeconds(60L * i), "NUMBER", i >= recentStart() ? 20.0 : coarse.applyAsDouble(i), "CELSIUS"));
        Finding f = of(run(rows), Category.FLATLINE);
        assertThat(f.status()).isEqualTo(Status.OBSERVED);
        assertThat(f.statistics().get("readingStep")).isEqualTo(1.0);
    }

    @Test
    void p6_068_aConstantBaselineHasNoDeviationScaleSoOutlierAndSpikeRefuseInsteadOfDividingByZero() {
        // 22.0 for 22 hours, then one reading of 30.0: the scale of "normal variation" is undefined, so nothing is called unusual
        List<RawRow> rows = minuteSeries(i -> i == recentStart() + 10 ? 30.0 : 22.0);
        List<Finding> f = run(rows);
        for (Category c : List.of(Category.OUTLIER, Category.SPIKE)) {
            assertThat(of(f, c).status()).as(c.name()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
            assertThat(of(f, c).reason()).isEqualTo("BASELINE_NOT_VARYING");
            assertThat(of(f, c).measurements()).isEmpty();
        }
    }
}
