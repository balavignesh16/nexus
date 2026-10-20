package com.nexus.intelligence.energy;

import com.nexus.intelligence.anomaly.Sample;
import com.nexus.intelligence.energy.EnergyResult.Kind;
import com.nexus.intelligence.energy.EnergyResult.Status;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** P6-221 and P6-223: energy arithmetic on stored readings, with its refusals. Pure: no database, no clock, no model. */
class EnergyAnalyzerTest {
    private static final Instant T0 = Instant.parse("2026-10-20T00:00:00Z");
    private static final EnergySettings S = EnergySettings.defaults();

    private static List<Sample> samples(long[] seconds, double[] values) {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < seconds.length; i++) out.add(new Sample("s" + i, T0.plusSeconds(seconds[i]), values[i]));
        return out;
    }

    private static List<Sample> regular(int count, long stepSeconds, double value) {
        long[] t = new long[count];
        double[] v = new double[count];
        for (int i = 0; i < count; i++) {
            t[i] = i * stepSeconds;
            v[i] = value;
        }
        return samples(t, v);
    }

    @Test
    void p6_221_aConstantPowerGivesTheEnergyOfTheSampledSpanAndSaysItIsAnEstimate() {
        EnergyResult r = EnergyAnalyzer.fromPower(regular(61, 60, 100.0), "W", "power", S);
        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.kind()).isEqualTo(Kind.INTEGRATED_FROM_POWER);
        assertThat(r.energyWh()).isCloseTo(100.0, within(1e-9));
        assertThat(r.averagePowerW()).isCloseTo(100.0, within(1e-9));
        assertThat(r.peakPowerW()).isEqualTo(100.0);
        assertThat(r.coverage()).isEqualTo(1.0);
        assertThat(r.samples()).isEqualTo(61);
        assertThat(r.spanSeconds()).isEqualTo(3600.0);
        assertThat(String.join(" ", r.notes())).contains("ESTIMATE").contains("not a metered energy value");
        assertThat(EnergyAnalyzer.fromPower(regular(61, 60, 100.0), "W", "power", S)).as("deterministic").isEqualTo(r);
    }

    @Test
    void p6_221_kilowattsAndMilliwattsAreConvertedAndAnUnknownUnitIsRefusedNotGuessed() {
        assertThat(EnergyAnalyzer.fromPower(regular(25, 300, 0.5), "kW", "power", S).energyWh()).isCloseTo(1000.0, within(1e-9));      // 0.5 kW for 2 hours
        assertThat(EnergyAnalyzer.fromPower(regular(61, 60, 2000.0), "mW", "power", S).energyWh()).isCloseTo(2.0, within(1e-9));       // 2 W for 1 hour
        EnergyResult btu = EnergyAnalyzer.fromPower(regular(10, 60, 5.0), "BTU/h", "power", S);
        assertThat(btu.status()).isEqualTo(Status.NOT_APPLICABLE);
        assertThat(btu.reason()).contains("not a power unit");
        assertThat(btu.energyWh()).isNull();
        assertThat(EnergyAnalyzer.fromPower(regular(10, 60, 5.0), null, "power", S).status()).isEqualTo(Status.NOT_APPLICABLE);
        assertThat(EnergyAnalyzer.powerToWatts(1, "W")).isEqualTo(1.0);
        assertThat(EnergyAnalyzer.powerToWatts(1, " KW ")).isEqualTo(1000.0);
        assertThat(EnergyAnalyzer.powerToWatts(1, "hp")).isNull();
        assertThat(EnergyAnalyzer.energyToWattHours(1, "kWh")).isEqualTo(1000.0);
        assertThat(EnergyAnalyzer.energyToWattHours(1, "J")).isNull();
    }

    @Test
    void p6_221_changingPowerIsIntegratedWithTheTrapezoidRule() {
        // 0 W rising to 100 W over a minute and falling back: two triangles of 3000 watt-seconds
        EnergyResult r = EnergyAnalyzer.fromPower(samples(new long[] {0, 60, 120}, new double[] {0, 100, 0}), "W", "power", S);
        assertThat(r.energyWh()).isCloseTo(6000.0 / 3600.0, within(1e-9));
        assertThat(r.peakPowerW()).isEqualTo(100.0);
        assertThat(r.averagePowerW()).isCloseTo(50.0, within(1e-9));
        assertThat(EnergyAnalyzer.fromPower(regular(30, 60, 0.0), "W", "power", S).energyWh()).as("zero power is zero energy, not missing").isEqualTo(0.0);
    }

    @Test
    void p6_223_aGapIsNotIntegratedAndIsReportedWithTheCoverage() {
        long[] t = new long[121];
        double[] v = new double[121];
        for (int i = 0; i < 60; i++) {
            t[i] = i * 10L;
            v[i] = 100;
        }
        for (int i = 60; i < 121; i++) {
            t[i] = 590L + 3600 + (i - 60) * 10L;      // an hour with no readings, then the same load again
            v[i] = 100;
        }
        EnergyResult r = EnergyAnalyzer.fromPower(samples(t, v), "W", "power", S);
        assertThat(r.gaps()).isEqualTo(1);
        assertThat(r.gapSeconds()).isEqualTo(3600.0);
        assertThat(r.coverage()).isLessThan(0.3);
        // only the two sampled stretches are integrated: 590 s and 600 s at 100 W, nothing invented for the hour in between
        assertThat(r.energyWh()).isCloseTo(100.0 * (590 + 600) / 3600.0, within(1e-9));
        assertThat(String.join(" ", r.notes())).contains("NOT integrated").contains("unknown");
    }

    @Test
    void p6_223_noUsableEvidenceIsInsufficientNotZero() {
        assertThat(EnergyAnalyzer.fromPower(List.of(), "W", "power", S).status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
        assertThat(EnergyAnalyzer.fromPower(regular(1, 60, 10), "W", "power", S).reason()).contains("at least two");
        EnergyResult wide = EnergyAnalyzer.fromPower(samples(new long[] {0, 86_400}, new double[] {50, 50}), "W", "power", S);
        assertThat(wide.status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
        assertThat(wide.reason()).contains("longer than the gap limit");
        assertThat(wide.energyWh()).isNull();
        EnergyResult negative = EnergyAnalyzer.fromPower(samples(new long[] {0, 60, 120}, new double[] {10, -5, 10}), "W", "power", S);
        assertThat(negative.status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
        assertThat(negative.reason()).contains("negative");
        assertThat(EnergyAnalyzer.fromPower(samples(new long[] {0, 60}, new double[] {10, Double.NaN}), "W", "power", S).status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void p6_221_p6_222_aMeteredCounterIsADifferenceNotAnEstimateAndAResetIsNotCounted() {
        EnergyResult r = EnergyAnalyzer.fromCounter(samples(new long[] {0, 3600, 7200}, new double[] {10.0, 10.5, 11.25}), "kWh", "energy");
        assertThat(r.kind()).isEqualTo(Kind.METERED_COUNTER);
        assertThat(r.energyWh()).isCloseTo(1250.0, within(1e-9));
        assertThat(r.averagePowerW()).isCloseTo(625.0, within(1e-9));
        assertThat(r.coverage()).isEqualTo(1.0);
        assertThat(String.join(" ", r.notes())).contains("accurate as that counter").doesNotContain("ESTIMATE");
        EnergyResult reset = EnergyAnalyzer.fromCounter(samples(new long[] {0, 60, 120, 180}, new double[] {10, 11, 0.5, 1.5}), "Wh", "energy");
        assertThat(reset.energyWh()).isCloseTo(2.0, within(1e-9));
        assertThat(reset.counterResets()).isEqualTo(1);
        assertThat(String.join(" ", reset.notes())).contains("counter reset").contains("not counted");
        assertThat(EnergyAnalyzer.fromCounter(samples(new long[] {0, 60}, new double[] {5, 5}), "Wh", "energy").energyWh()).isEqualTo(0.0);
        assertThat(EnergyAnalyzer.fromCounter(samples(new long[] {0, 60}, new double[] {5, 6}), "joule", "energy").status()).isEqualTo(Status.NOT_APPLICABLE);
        assertThat(EnergyAnalyzer.fromCounter(regular(1, 60, 5), "Wh", "energy").status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void p6_221_theSettingsAreValidated() {
        assertThatThrownBy(() -> new EnergySettings(24, List.of(), List.of(), 5, 60, 600, 5000, 200, 5, 300)).hasMessageContaining("power-keys");
        assertThatThrownBy(() -> new EnergySettings(24, List.of("Power"), List.of(), 5, 60, 600, 5000, 200, 5, 300)).hasMessageContaining("not a capability key");
        assertThatThrownBy(() -> new EnergySettings(24, List.of("power"), List.of(), 1, 60, 600, 5000, 200, 5, 300)).hasMessageContaining("gap-factor");
        assertThatThrownBy(() -> new EnergySettings(24, List.of("power"), List.of(), 5, 600, 60, 5000, 200, 5, 300)).hasMessageContaining("max-gap-seconds");
        assertThatThrownBy(() -> new EnergySettings(24, List.of("power"), List.of(), 5, 60, 600, 5000, 0, 5, 300)).hasMessageContaining("max-devices");
    }
}
