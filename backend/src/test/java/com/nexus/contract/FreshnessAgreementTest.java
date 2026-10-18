package com.nexus.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.outbox.CommandDeliveryLimits;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5-085, backend leg. The backend does not evaluate command freshness (the device does); what it owns is the delivery window it must
 * stay inside so that the device's rule accepts the command. This test pins that window to the contract: the backend constant, the
 * manifest's freshness cases and the ESP32 profile in versions.json state the same 30 s absolute window, and every delivery the backend
 * allows (up to MAX_DELIVERY_WINDOW after the payload timestamp, with the whole clock-uncertainty margin spent in either direction) is
 * FRESH under the device's absolute rule. The ESP32 library, the Python SDK, the simulator and the validator are compared with the
 * same manifest offsets in sdk/python/tests/test_esp32_library.py.
 */
class FreshnessAgreementTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The firmware's rule (firmware/src/main.cpp): STALE when the absolute difference exceeds the window. */
    private static boolean stale(long nowSeconds, long timestampSeconds, long windowSeconds) {
        return Math.abs(nowSeconds - timestampSeconds) > windowSeconds;
    }

    @Test
    void p5_085_theBackendWindowIsTheContractsWindow() throws IOException {
        Path root = repositoryRoot();
        JsonNode manifest = JSON.readTree(Files.readAllBytes(root.resolve("contracts/fixtures/firmware-v1/manifest.json")));
        JsonNode versions = JSON.readTree(Files.readAllBytes(root.resolve("contracts/versions.json")));
        long manifestWindow = manifest.get("freshnessCases").get("windowSeconds").asLong();
        long profileWindow = versions.get("profiles").get("esp32-legacy-v1").get("commandFreshnessWindowSeconds").asLong();
        assertThat(CommandDeliveryLimits.FIRMWARE_FRESHNESS_WINDOW).isEqualTo(Duration.ofSeconds(manifestWindow));
        assertThat(profileWindow).isEqualTo(manifestWindow).isEqualTo(30);
        assertThat(versions.get("profiles").get("esp32-legacy-v1").get("commandFreshnessRule").asText()).isEqualTo("absolute");
    }

    @Test
    void p5_085_theManifestOffsetsGiveTheSameVerdictsAsTheDevicesRule() throws IOException {
        JsonNode cases = JSON.readTree(Files.readAllBytes(repositoryRoot().resolve("contracts/fixtures/firmware-v1/manifest.json"))).get("freshnessCases");
        long window = CommandDeliveryLimits.FIRMWARE_FRESHNESS_WINDOW.toSeconds();
        long timestamp = 1_767_225_600L;
        java.util.List<Long> expectStale = new java.util.ArrayList<>();
        for (JsonNode offset : cases.get("expectStale")) expectStale.add(offset.asLong());
        for (JsonNode offset : cases.get("offsetsSeconds")) {
            long o = offset.asLong();
            assertThat(stale(timestamp + o, timestamp, window)).as("offset %d", o).isEqualTo(expectStale.contains(o));
        }
    }

    @Test
    void p5_085_everyDeliveryTheBackendAllowsIsFreshWhateverTheClockSkewWithinTheBudget() {
        long window = CommandDeliveryLimits.FIRMWARE_FRESHNESS_WINDOW.toSeconds();
        long margin = CommandDeliveryLimits.CLOCK_UNCERTAINTY_MARGIN.toSeconds();
        long maxDelivery = CommandDeliveryLimits.MAX_DELIVERY_WINDOW.toSeconds();
        assertThat(maxDelivery + margin).isLessThanOrEqualTo(window);
        long timestamp = 1_767_225_600L;
        // the device sees the command up to maxDelivery seconds late, plus up to the whole margin of skew and delay in either direction
        for (long late = 0; late <= maxDelivery; ++late) {
            for (long skew = -margin; skew <= margin; ++skew) {
                assertThat(stale(timestamp + late + skew, timestamp, window)).as("late %d skew %d", late, skew).isFalse();
            }
        }
        // and one second beyond the ceiling plus the margin is the first stale delivery
        assertThat(stale(timestamp + maxDelivery + margin + 1, timestamp, window)).isTrue();
    }

    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("contracts/versions.json"))) directory = directory.getParent();
        if (directory == null) throw new AssertionError("contracts/versions.json not found above the working directory");
        return directory;
    }
}
