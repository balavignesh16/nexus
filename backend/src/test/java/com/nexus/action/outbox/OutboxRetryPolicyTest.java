package com.nexus.action.outbox;

import com.nexus.action.outbox.OutboxDeliveryOperations.AttemptResult;
import com.nexus.action.outbox.OutboxRetryPolicy.Kind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure retry decision (P4.3): what may be retried, how long to wait, when to stop. No database, no clock: the jitter source
 * and the remaining window are inputs, so every boundary is exact. Covers P4-034, P4-039 and P4-040 (unit layer).
 */
class OutboxRetryPolicyTest {

    private static CommandOutboxSettings defaults() {
        return FakeBroker.settings(1, Duration.ofSeconds(3), Duration.ofSeconds(6));
    }

    private static OutboxRetryPolicy policy(double jitter) {
        return new OutboxRetryPolicy(defaults(), () -> jitter);
    }

    // ------------------------------------------------------------------------------------------- what may be retried

    @Test
    void onlyAProvenNonPublicationCanBeConsideredForARetry() {
        OutboxRetryPolicy p = policy(0.5);
        assertTrue(p.decide(AttemptResult.NOT_SENT, 1, 3, Duration.ofSeconds(10), "NOT_SENT_X").retry());
        // an uncertain outcome may already be on the wire: it is refused outright, not "decided"
        assertThrows(IllegalArgumentException.class, () -> p.decide(AttemptResult.UNCERTAIN, 1, 3, Duration.ofSeconds(10), "PUBLISH_TIMEOUT"));
        assertThrows(IllegalArgumentException.class, () -> p.decide(AttemptResult.CONFIRMED, 1, 3, Duration.ofSeconds(10), null));
    }

    @Test
    void everyPublisherResultOtherThanNotSentIsRefused() {
        OutboxRetryPolicy p = policy(0.0);
        for (AttemptResult r : AttemptResult.values()) {
            if (r == AttemptResult.NOT_SENT) continue;
            assertThrows(IllegalArgumentException.class, () -> p.decide(r, 1, 3, Duration.ofSeconds(14), "C"), r + " must never reach a retry");
        }
    }

    // ---------------------------------------------------------------------------------------- attempt budget and window

    @Test
    void theLastAttemptIsDeadLetteredAsExhaustedAndNeverRetried() {
        OutboxRetryPolicy p = policy(0.5);
        var d = p.decide(AttemptResult.NOT_SENT, 3, 3, Duration.ofSeconds(14), "NOT_SENT_X");
        assertEquals(Kind.DEAD_LETTER, d.kind());
        assertEquals(OutboxTerminalReason.RETRY_EXHAUSTED, d.reason());
        assertTrue(p.decide(AttemptResult.NOT_SENT, 2, 3, Duration.ofSeconds(14), "C").retry());
        assertEquals(OutboxTerminalReason.RETRY_EXHAUSTED, p.decide(AttemptResult.NOT_SENT, 1, 1, Duration.ofSeconds(14), "C").reason(), "a budget of one never retries");
    }

    @Test
    void aRetryThatCannotStartBeforeTheWindowEndsIsDeadLetteredAsExpiredWithExactBoundary() {
        OutboxRetryPolicy p = policy(0.5);                       // jitter 0.5 = exactly the base delay: 500 ms after attempt 1
        assertEquals(Duration.ofMillis(500), p.delayAfter(1));
        assertTrue(p.decide(AttemptResult.NOT_SENT, 1, 3, Duration.ofMillis(501), "C").retry(), "starts 1 ms before expiry");
        var atEdge = p.decide(AttemptResult.NOT_SENT, 1, 3, Duration.ofMillis(500), "C");
        assertEquals(Kind.DEAD_LETTER, atEdge.kind(), "no attempt starts AT expires_at");
        assertEquals(OutboxTerminalReason.EXPIRED, atEdge.reason());
        assertEquals(OutboxTerminalReason.EXPIRED, p.decide(AttemptResult.NOT_SENT, 1, 3, Duration.ZERO, "C").reason());
        assertEquals(OutboxTerminalReason.EXPIRED, p.decide(AttemptResult.NOT_SENT, 1, 3, Duration.ofSeconds(-3), "C").reason(), "an already expired window");
    }

    @Test
    void theBudgetIsCheckedBeforeTheWindowSoExhaustionIsNeverReportedAsExpiry() {
        var d = policy(0.5).decide(AttemptResult.NOT_SENT, 3, 3, Duration.ZERO, "C");
        assertEquals(OutboxTerminalReason.RETRY_EXHAUSTED, d.reason());
    }

    // -------------------------------------------------------------------------------------------------- backoff (P4-034)

    @Test
    void theDelayFollowsInitialTimesMultiplierCappedAndJitteredSymmetricallyByTwentyPercent() {
        // base: 500 ms, 1000 ms, 2000 ms, 4000 ms (cap), 4000 ms
        long[] base = {500, 1000, 2000, 4000};
        // CommandOutboxSettings allows up to 5 attempts, so the delays after attempts 1..4 are the ones that can ever be used
        CommandOutboxSettings five = new CommandOutboxSettings(false, Duration.ofSeconds(20), 5, Duration.ofSeconds(1), Duration.ofMillis(500), 2.0,
                Duration.ofSeconds(4), 0.2, Duration.ofSeconds(6), 10, Duration.ofMillis(250), Duration.ofSeconds(1), 4);
        for (int attempt = 1; attempt <= 4; attempt++) {
            long b = base[attempt - 1];
            assertEquals(b, new OutboxRetryPolicy(five, () -> 0.5).delayAfter(attempt).toMillis(), "no jitter at u=0.5, attempt " + attempt);
            assertEquals(Math.round(b * 0.8), new OutboxRetryPolicy(five, () -> 0.0).delayAfter(attempt).toMillis(), "minus 20% at u=0");
            long high = new OutboxRetryPolicy(five, () -> 0.999999).delayAfter(attempt).toMillis();
            assertTrue(high <= Math.round(b * 1.2) + 1 && high >= b, "plus 20% at u close to 1: " + high);
            assertTrue(high <= five.maxBackoffBeforeAttempt(attempt + 1).toMillis(), "never above the configured ceiling");
        }
    }

    @Test
    void noDelayIsEverZeroNegativeOrAboveTheJitteredCapForAnyJitterValue() {
        CommandOutboxSettings s = defaults();
        for (int attempt = 1; attempt <= 4; attempt++) {
            for (double u = 0.0; u < 1.0; u += 0.0137) {
                final double jitter = u;
                Duration delay = new OutboxRetryPolicy(s, () -> jitter).delayAfter(attempt);
                assertTrue(delay.toMillis() >= 1, "no zero-delay busy retry: " + delay);
                assertTrue(delay.compareTo(Duration.ofMillis((long) Math.ceil(4000 * 1.2))) <= 0, "bounded by cap x 1.2: " + delay);
                assertTrue(delay.compareTo(s.maxBackoffBeforeAttempt(attempt + 1)) <= 0);
            }
        }
    }

    @Test
    void aJitterSourceOutsideTheUnitIntervalIsRefusedRatherThanProducingAnArbitraryDelay() {
        for (double bad : new double[]{-0.0001, 1.0, 2.5, Double.NaN})
            assertThrows(IllegalStateException.class, () -> policy(bad).delayAfter(1), "jitter " + bad);
    }

    @Test
    void theDelaySequenceIsDeterministicForAFixedSource() {
        DoubleSupplier fixed = () -> 0.25;
        var a = new OutboxRetryPolicy(defaults(), fixed);
        var b = new OutboxRetryPolicy(defaults(), fixed);
        assertEquals(List.of(a.delayAfter(1), a.delayAfter(2)), List.of(b.delayAfter(1), b.delayAfter(2)));
        assertThrows(IllegalArgumentException.class, () -> a.delayAfter(0));
    }

    // ------------------------------------------- worst-case schedule, recomputed independently from the real settings (R11, R12)

    @Test
    void theWorstCaseScheduleIsRecomputedFromTheActualBudgetAndJitterNotAssumed() {
        CommandOutboxSettings s = defaults();                    // 3 attempts, 3 s timeout, 0.5 s x2, cap 4 s, jitter 20%, poll 250 ms
        // independent arithmetic (does not call the settings class):
        double attempts = 3 * 3.0;                               // every attempt runs to its timeout
        double backoffs = 0.5 * 1.2 + 1.0 * 1.2;                 // the two backoffs at their jittered maximum = 1.8 s
        assertEquals(10.8, attempts + backoffs, 1e-9);
        assertEquals(10_800, s.worstCaseSchedule().toMillis(), "the stated 10.8 s is correct for these defaults, and is now proven rather than assumed");
        // the true end-to-end worst case also pays one poll interval before each claim and the one-second timestamp truncation
        double endToEnd = attempts + backoffs + 3 * 0.25 + 1.0;
        assertEquals(12.55, endToEnd, 1e-9);
        assertEquals(12_550, s.worstCaseEndToEnd().toMillis());
        assertTrue(s.worstCaseEndToEnd().compareTo(s.deliveryWindow()) <= 0, "fits the 15 s window with 2.45 s to spare");
    }

    @Test
    void aWindowTooShortForTheRealEndToEndScheduleIsRejectedAtStartup() {
        // 12 s window: 10.8 s of attempts and backoffs would fit, but the poll latency and the timestamp truncation (12.55 s) do not
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new CommandOutboxSettings(true, Duration.ofSeconds(12), 3,
                Duration.ofSeconds(3), Duration.ofMillis(500), 2.0, Duration.ofSeconds(4), 0.2, Duration.ofSeconds(6), 10, Duration.ofMillis(250),
                Duration.ofSeconds(1), 4));
        assertTrue(e.getMessage().contains("end-to-end"), e.getMessage());
        // 13 s is enough
        assertTrue(new CommandOutboxSettings(true, Duration.ofSeconds(13), 3, Duration.ofSeconds(3), Duration.ofMillis(500), 2.0, Duration.ofSeconds(4),
                0.2, Duration.ofSeconds(6), 10, Duration.ofMillis(250), Duration.ofSeconds(1), 4).enabled());
    }

    @Test
    void theLargestAllowedBudgetCannotBeConfiguredWithinTheTwentySecondCeiling() {
        // 5 attempts x 3 s = 15 s plus backoffs 0.6+1.2+2.4+4.8 = 9 s: 24 s > 20 s ceiling, so such a configuration cannot exist
        assertThrows(IllegalStateException.class, () -> new CommandOutboxSettings(true, Duration.ofSeconds(20), 5, Duration.ofSeconds(3), Duration.ofMillis(500),
                2.0, Duration.ofSeconds(4), 0.2, Duration.ofSeconds(6), 10, Duration.ofMillis(250), Duration.ofSeconds(1), 4));
    }
}
