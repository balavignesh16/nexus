package com.nexus.action.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ledger P4-121 and P4-122 (backend side). A TEST-SIDE model of the committed firmware's freshness check
 * ({@code firmware/src/main.cpp}: {@code age = |now - commandTimestamp|}, rejected as STALE when {@code age > 30}, in BOTH directions)
 * is used to verify the delivery window arithmetic. The firmware and the simulator are not changed; the model is a function in this
 * file, so no production or device code is touched (decision A8).
 */
class FirmwareFreshnessBoundaryTest {

    /** The firmware check, verbatim in meaning: STALE when the absolute age exceeds 30 seconds. */
    private static boolean firmwareAccepts(long deviceNowEpochSeconds, long commandTimestampEpochSeconds) {
        long age = Math.abs(deviceNowEpochSeconds - commandTimestampEpochSeconds);
        return age <= CommandDeliveryLimits.FIRMWARE_FRESHNESS_WINDOW.toSeconds();
    }

    private static final long TIMESTAMP = 1_800_000_000L;

    @Test
    void theModelRejectsOnlyAnAbsoluteAgeAboveThirtySecondsInBothDirections() {
        assertTrue(firmwareAccepts(TIMESTAMP, TIMESTAMP));
        assertTrue(firmwareAccepts(TIMESTAMP + 30, TIMESTAMP), "exactly 30 s is still accepted");
        assertFalse(firmwareAccepts(TIMESTAMP + 31, TIMESTAMP), "31 s is STALE");
        assertTrue(firmwareAccepts(TIMESTAMP - 30, TIMESTAMP), "a device clock BEHIND the command is judged by the absolute difference");
        assertFalse(firmwareAccepts(TIMESTAMP - 31, TIMESTAMP));
    }

    /** The age at the device of a command whose attempt STARTS {@code startedAfterTimestamp} after the payload timestamp. */
    private static long ageAtDevice(Duration startedAfterTimestamp, long deviceClockErrorSeconds, long networkDelaySeconds) {
        long deviceNow = TIMESTAMP + startedAfterTimestamp.toSeconds() + networkDelaySeconds + deviceClockErrorSeconds;
        return Math.abs(deviceNow - TIMESTAMP);
    }

    @Test
    void aCommandPublishedAtTheEdgeOfEveryAllowedWindowIsAcceptedByTheDeviceWithinTheBudgetedMargin() {
        long truncation = CommandDeliveryLimits.TIMESTAMP_PRECISION_BUDGET.toSeconds();
        long skew = CommandDeliveryLimits.CLOCK_SKEW_BUDGET.toSeconds();
        long delay = CommandDeliveryLimits.NETWORK_DELAY_BUDGET.toSeconds();
        for (long window = CommandDeliveryLimits.MIN_DELIVERY_WINDOW.toSeconds(); window <= CommandDeliveryLimits.MAX_DELIVERY_WINDOW.toSeconds(); window++) {
            // the LAST permitted attempt starts just before expires_at = timestamp + window; the timestamp itself was truncated by up to a second
            Duration lastStart = Duration.ofSeconds(window).plusSeconds(truncation);
            for (long clockError : new long[]{-skew, 0, skew}) {
                long age = ageAtDevice(lastStart, clockError, delay);
                assertTrue(firmwareAccepts(TIMESTAMP + age, TIMESTAMP), "window " + window + " s, device clock error " + clockError + " s: age " + age + " s");
                assertTrue(age <= CommandDeliveryLimits.FIRMWARE_FRESHNESS_WINDOW.toSeconds() - CommandDeliveryLimits.SAFETY_ALLOWANCE.toSeconds(),
                        "the safety allowance of " + CommandDeliveryLimits.SAFETY_ALLOWANCE + " is left over");
            }
        }
    }

    @Test
    void theCeilingIsExactlyTheLargestWindowThatKeepsTheWorstCaseAgeInsideTheDeviceCheck() {
        long worstCaseExtras = CommandDeliveryLimits.TIMESTAMP_PRECISION_BUDGET.toSeconds() + CommandDeliveryLimits.CLOCK_SKEW_BUDGET.toSeconds()
                + CommandDeliveryLimits.NETWORK_DELAY_BUDGET.toSeconds();                                  // 8 s
        long ceiling = CommandDeliveryLimits.MAX_DELIVERY_WINDOW.toSeconds();                              // 20 s
        assertEquals(20, ceiling);
        assertEquals(28, ceiling + worstCaseExtras, "the worst possible age at the device at the ceiling");
        assertTrue(ceiling + worstCaseExtras <= 30);
        // a start that is allowed to slip past expires_at by even the safety allowance plus one second would be rejected as stale
        long pastEdge = ceiling + worstCaseExtras + CommandDeliveryLimits.SAFETY_ALLOWANCE.toSeconds() + 1;
        assertFalse(firmwareAccepts(TIMESTAMP + pastEdge, TIMESTAMP), "an attempt allowed after the window can be rejected by the device: which is why none starts at or after expires_at");
    }

    @Test
    void aWindowAboveTheCeilingCannotBeConfiguredSoNoCommandPastItIsEverProduced() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new CommandOutboxSettings(true, Duration.ofSeconds(21), 1,
                Duration.ofSeconds(3), Duration.ofMillis(500), 2.0, Duration.ofSeconds(4), 0.2, Duration.ofSeconds(6), 10, Duration.ofMillis(250),
                Duration.ofSeconds(1), 4));
        assertTrue(e.getMessage().contains("must not exceed"), e.getMessage());
        // the ceiling itself is valid
        assertTrue(new CommandOutboxSettings(true, Duration.ofSeconds(20), 3, Duration.ofSeconds(3), Duration.ofMillis(500), 2.0, Duration.ofSeconds(4),
                0.2, Duration.ofSeconds(6), 10, Duration.ofMillis(250), Duration.ofSeconds(1), 4).enabled());
    }

    @Test
    void theRetryPolicyNeverSchedulesAStartAtOrAfterTheEndOfTheWindowSoTheEdgeArithmeticHolds() {
        var policy = new OutboxRetryPolicy(FakeBroker.settings(1, Duration.ofSeconds(3), Duration.ofSeconds(6)), () -> 0.999);
        Duration delay = policy.delayAfter(1);
        // remaining time equal to or below the delay: no retry; one millisecond more: a retry that starts strictly before expiry
        assertFalse(policy.decide(OutboxDeliveryOperations.AttemptResult.NOT_SENT, 1, 3, delay, "C").retry());
        assertTrue(policy.decide(OutboxDeliveryOperations.AttemptResult.NOT_SENT, 1, 3, delay.plusMillis(1), "C").retry());
    }
}
