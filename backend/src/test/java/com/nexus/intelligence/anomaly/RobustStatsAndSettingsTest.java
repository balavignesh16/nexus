package com.nexus.intelligence.anomaly;

import com.nexus.intelligence.recommend.RecommendationSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The order statistics are exact and order-independent, and every detector and recommendation setting is range-checked at startup. */
class RobustStatsAndSettingsTest {
    @Test
    void p6_057_medianMadAndPercentileAreExactAndIndependentOfInputOrder() {
        List<Double> v = new ArrayList<>(List.of(1.0, 2.0, 3.0, 4.0, 100.0));
        assertThat(RobustStats.median(v)).isEqualTo(3.0);
        assertThat(RobustStats.mad(v)).isEqualTo(1.0); // |x-3| = 2,1,0,1,97 -> median 1
        assertThat(RobustStats.median(List.of(1.0, 2.0, 3.0, 4.0))).isEqualTo(2.5);
        assertThat(RobustStats.percentile(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0), 90)).isEqualTo(9.0); // nearest rank
        assertThat(RobustStats.percentile(List.of(5.0), 99)).isEqualTo(5.0);
        for (int seed = 0; seed < 20; seed++) {
            List<Double> shuffled = new ArrayList<>(v);
            Collections.shuffle(shuffled, new Random(seed));
            assertThat(RobustStats.median(shuffled)).isEqualTo(3.0);
            assertThat(RobustStats.mad(shuffled)).isEqualTo(1.0);
        }
        assertThatThrownBy(() -> RobustStats.median(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void p6_057_theScaleFallsBackWhenMoreThanHalfTheBaselineIsIdenticalAndIsUndefinedWhenNothingVaries() {
        RobustStats.Scale mostlyEqual = RobustStats.scale(List.of(5.0, 5.0, 5.0, 5.0, 5.0, 6.0, 4.0));
        assertThat(mostlyEqual.mad()).isZero();
        assertThat(mostlyEqual.defined()).isTrue();
        assertThat(mostlyEqual.form()).isEqualTo("MEAN_ABSOLUTE_DEVIATION");
        assertThat(mostlyEqual.score(9.0)).isGreaterThan(4.0);
        assertThat(RobustStats.scale(List.of(7.0, 7.0, 7.0)).defined()).isFalse();
        assertThat(RobustStats.scale(List.of(1.0, 2.0, 3.0, 4.0, 5.0)).form()).isEqualTo("MAD");
    }

    @Test
    void p6_067_p6_071_everySettingIsRangeCheckedSoABadConfigurationStopsStartup() {
        AnomalySettings d = AnomalySettings.defaults();
        assertThat(d.minBaselineSamples()).isEqualTo(100);
        assertThat(d.outlierK()).isEqualTo(4.0);
        assertThatThrownBy(() -> new AnomalySettings(0, 120, 100, 10, 4, 5, 5, 60, 300, 5000, 20, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AnomalySettings(721, 120, 100, 10, 4, 5, 5, 60, 300, 5000, 20, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AnomalySettings(24, 4, 100, 10, 4, 5, 5, 60, 300, 5000, 20, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AnomalySettings(24, 120, 9, 10, 4, 5, 5, 60, 300, 5000, 20, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AnomalySettings(24, 120, 100, 10, 1.0, 5, 5, 60, 300, 5000, 20, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AnomalySettings(24, 120, 100, 10, 4, 5, 5, 60, 4000, 5000, 20, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AnomalySettings(24, 120, 100, 10, 4, 5, 5, 60, 300, 50, 20, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AnomalySettings(24, 120, 100, 10, 4, 5, 5, 60, 300, 5000, 0, 10)).isInstanceOf(IllegalStateException.class);
        RecommendationSettings r = RecommendationSettings.defaults();
        assertThat(r.factHours()).isEqualTo(168);
        assertThatThrownBy(() -> new RecommendationSettings(0, 3, 100, 24, 50, 50, 50, 168, 0.5)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RecommendationSettings(3, 3, 10, 24, 50, 50, 50, 168, 0.5)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RecommendationSettings(3, 3, 100, 24, 500, 50, 50, 168, 0.5)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RecommendationSettings(3, 3, 100, 24, 50, 50, 50, 721, 0.5)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RecommendationSettings(3, 3, 100, 24, 50, 50, 50, 168, 0.0)).isInstanceOf(IllegalStateException.class);
    }
}
