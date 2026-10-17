package com.nexus.action.outbox;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * The single, pure decision of what happens to a command after one delivery attempt that did not complete (contract R10, R12,
 * ticket P4.3). It holds no state and performs no I/O, so every rule is unit-testable with an injected jitter source, and it is
 * the ONLY place that decides whether a command may be tried again.
 *
 * <ul>
 * <li><b>Only a proven non-publication is retried.</b> {@link OutboxDeliveryOperations.AttemptResult#NOT_SENT} is the one outcome that
 *     may lead to {@link Kind#RETRY}. {@code UNCERTAIN} never does: the bytes may already be on the wire or in the client's
 *     in-flight state, so another publish could deliver the command twice or out of order (the committed firmware has no
 *     commandId deduplication). {@code CONFIRMED} needs no retry. Anything else that reaches this method is refused.</li>
 * <li><b>Bounded attempts.</b> The attempt that just ended is {@code attempt}; when it was the last one the command is dead-lettered
 *     as {@link OutboxTerminalReason#RETRY_EXHAUSTED}.</li>
 * <li><b>The delivery window is never extended.</b> A retry is scheduled only if the jittered delay ends strictly before the end
 *     of the window; otherwise the command is dead-lettered as {@link OutboxTerminalReason#EXPIRED} (it was never sent).</li>
 * <li><b>Backoff.</b> The delay before attempt n+1 is {@code min(max-backoff, initial-backoff x multiplier^(n-1))}, then jittered
 *     SYMMETRICALLY by plus or minus {@code jitter-fraction} (the contract's definition, R12), and never below one millisecond,
 *     so no retry is a busy loop and no delay exceeds {@code max-backoff x (1 + jitter-fraction)}.</li>
 * </ul>
 * The remaining window is read from the DATABASE clock by the caller; this class never reads a clock.
 */
public final class OutboxRetryPolicy {

    public enum Kind { RETRY, DEAD_LETTER }

    /**
     * @param delay  the wait before the next attempt (only for RETRY)
     * @param reason the terminal reason (only for DEAD_LETTER)
     */
    public record Decision(Kind kind, Duration delay, OutboxTerminalReason reason, String code) {
        public boolean retry() { return kind == Kind.RETRY; }
    }

    private static final Duration MIN_DELAY = Duration.ofMillis(1);

    private final CommandOutboxSettings settings;
    private final DoubleSupplier jitter;

    /** @param jitter uniform value in [0, 1); {@code ThreadLocalRandom.current()::nextDouble} in production */
    public OutboxRetryPolicy(CommandOutboxSettings settings, DoubleSupplier jitter) {
        this.settings = settings;
        this.jitter = jitter;
    }

    /** The jittered wait after attempt {@code failedAttempt} (1-based) and before the next one. Always positive and bounded. */
    public Duration delayAfter(int failedAttempt) {
        if (failedAttempt < 1) throw new IllegalArgumentException("failedAttempt must be at least 1");
        double base = settings.baseBackoffBeforeAttempt(failedAttempt + 1).toMillis();
        double u = jitter.getAsDouble();
        if (Double.isNaN(u) || u < 0.0 || u >= 1.0) throw new IllegalStateException("the jitter source must return a value in [0, 1)");
        double millis = base * (1.0 + settings.jitterFraction() * (2.0 * u - 1.0));
        Duration delay = Duration.ofMillis(Math.max(MIN_DELAY.toMillis(), (long) Math.ceil(millis)));
        Duration ceiling = settings.maxBackoffBeforeAttempt(failedAttempt + 1);
        return delay.compareTo(ceiling) > 0 ? ceiling : delay;
    }

    /**
     * @param result          what the publisher reported for the attempt that just ended
     * @param attempt         the number of that attempt (1-based)
     * @param maxAttempts     the attempt budget stored on the command
     * @param remainingWindow time left until {@code expires_at} on the DATABASE clock; may be zero or negative
     * @param code            the stable machine code of the failure
     */
    public Decision decide(OutboxDeliveryOperations.AttemptResult result, int attempt, int maxAttempts, Duration remainingWindow, String code) {
        if (result != OutboxDeliveryOperations.AttemptResult.NOT_SENT)
            throw new IllegalArgumentException("only a proven NOT_SENT outcome can be considered for a retry, not " + result);
        if (attempt < 1 || maxAttempts < 1) throw new IllegalArgumentException("attempt and maxAttempts must be at least 1");
        if (attempt >= maxAttempts) return new Decision(Kind.DEAD_LETTER, null, OutboxTerminalReason.RETRY_EXHAUSTED, code);
        Duration delay = delayAfter(attempt);
        if (remainingWindow.compareTo(delay) <= 0) return new Decision(Kind.DEAD_LETTER, null, OutboxTerminalReason.EXPIRED, code);
        return new Decision(Kind.RETRY, delay, null, code);
    }
}
