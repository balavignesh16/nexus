package com.nexus.action.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Outbox feature flag and delivery limits (contract R12, R22), validated when the application starts: an invalid
 * combination throws, so the application refuses to start rather than run with unsafe delivery bounds.
 *
 * P4.1 only reads and validates configuration. Nothing consults {@link #enabled()} yet; production dispatch is
 * unchanged and the flag defaults to false (decision D7).
 *
 * Defaults and why (all overridable; every override is validated):
 * <ul>
 * <li>delivery-window 15 s: queue wait counts against it; inside the 20 s ceiling derived from the firmware's
 *     30 s freshness check minus a 10 s uncertainty margin ({@link CommandDeliveryLimits}).</li>
 * <li>max-attempts 3: three tries fit the window with room for backoff; more would only push retries toward the
 *     window edge where the device rejects them as stale.</li>
 * <li>attempt-timeout 3 s: the dispatcher must bound each publish itself. The shared MQTT client waits up to its
 *     connect timeout plus grace (15 s, MQTTConfiguration) for a blocking publish, which would consume the
 *     whole window, so P4.2 may not use the blocking call.</li>
 * <li>backoff 0.5 s x2, cap 4 s, jitter 20%: short enough to retry inside the window, jittered so instances do
 *     not retry in lockstep.</li>
 * <li>lease-duration 6 s: longer than one attempt plus a 2 s margin, short enough that a crashed claim is
 *     recovered while the command can still be retried inside the window.</li>
 * </ul>
 */
@Component
public class CommandOutboxSettings {

    private final boolean enabled;
    private final Duration deliveryWindow;
    private final int maxAttempts;
    private final Duration attemptTimeout;
    private final Duration initialBackoff;
    private final double backoffMultiplier;
    private final Duration maxBackoff;
    private final double jitterFraction;
    private final Duration leaseDuration;
    private final int claimBatchSize;
    private final Duration pollInterval;
    private final Duration expiryScanInterval;
    private final int workers;

    /** Same as the full constructor with the default of four dispatcher workers. */
    public CommandOutboxSettings(boolean enabled, Duration deliveryWindow, int maxAttempts, Duration attemptTimeout,
            Duration initialBackoff, double backoffMultiplier, Duration maxBackoff, double jitterFraction,
            Duration leaseDuration, int claimBatchSize, Duration pollInterval, Duration expiryScanInterval) {
        this(enabled, deliveryWindow, maxAttempts, attemptTimeout, initialBackoff, backoffMultiplier, maxBackoff,
                jitterFraction, leaseDuration, claimBatchSize, pollInterval, expiryScanInterval, 4);
    }

    /**
     * The Spring constructor. The flag is read as TEXT and parsed strictly by {@link #parseEnabled}, so the single
     * authoritative decision "is the outbox enabled" can never differ between components: every consumer (acceptance,
     * coordinator, executor guard, dispatcher) asks this bean, and a value that is not exactly true or false stops startup
     * instead of being guessed (Spring's own boolean conversion would also accept 1, on and yes).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public CommandOutboxSettings(
            @Value("${nexus.command.outbox.enabled:false}") String enabledText,
            @Value("${nexus.command.outbox.delivery-window:PT15S}") Duration deliveryWindow,
            @Value("${nexus.command.outbox.max-attempts:3}") int maxAttempts,
            @Value("${nexus.command.outbox.attempt-timeout:PT3S}") Duration attemptTimeout,
            @Value("${nexus.command.outbox.initial-backoff:PT0.5S}") Duration initialBackoff,
            @Value("${nexus.command.outbox.backoff-multiplier:2.0}") double backoffMultiplier,
            @Value("${nexus.command.outbox.max-backoff:PT4S}") Duration maxBackoff,
            @Value("${nexus.command.outbox.jitter-fraction:0.2}") double jitterFraction,
            @Value("${nexus.command.outbox.lease-duration:PT6S}") Duration leaseDuration,
            @Value("${nexus.command.outbox.claim-batch-size:10}") int claimBatchSize,
            @Value("${nexus.command.outbox.poll-interval:PT0.25S}") Duration pollInterval,
            @Value("${nexus.command.outbox.expiry-scan-interval:PT1S}") Duration expiryScanInterval,
            @Value("${nexus.command.outbox.workers:4}") int workers) {
        this(parseEnabled(enabledText), deliveryWindow, maxAttempts, attemptTimeout, initialBackoff, backoffMultiplier, maxBackoff,
                jitterFraction, leaseDuration, claimBatchSize, pollInterval, expiryScanInterval, workers);
    }

    /** Only the exact words true and false (any case, surrounding blanks ignored) are accepted; anything else is a startup error. */
    public static boolean parseEnabled(String text) {
        String t = text == null ? "" : text.trim();
        if (t.equalsIgnoreCase("true")) return true;
        if (t.equalsIgnoreCase("false")) return false;
        throw new IllegalStateException("Invalid nexus.command.outbox.enabled value '" + t + "': it must be exactly true or false");
    }

    public CommandOutboxSettings(
            boolean enabled,
            Duration deliveryWindow,
            int maxAttempts,
            Duration attemptTimeout,
            Duration initialBackoff,
            double backoffMultiplier,
            Duration maxBackoff,
            double jitterFraction,
            Duration leaseDuration,
            int claimBatchSize,
            Duration pollInterval,
            Duration expiryScanInterval,
            int workers) {
        this.enabled = enabled;
        this.deliveryWindow = deliveryWindow;
        this.maxAttempts = maxAttempts;
        this.attemptTimeout = attemptTimeout;
        this.initialBackoff = initialBackoff;
        this.backoffMultiplier = backoffMultiplier;
        this.maxBackoff = maxBackoff;
        this.jitterFraction = jitterFraction;
        this.leaseDuration = leaseDuration;
        this.claimBatchSize = claimBatchSize;
        this.pollInterval = pollInterval;
        this.expiryScanInterval = expiryScanInterval;
        this.workers = workers;
        List<String> problems = problems();
        if (!problems.isEmpty())
            throw new IllegalStateException("Invalid nexus.command.outbox configuration: " + String.join("; ", problems));
    }

    /** All violated rules, empty when the configuration is valid. */
    public List<String> problems() {
        List<String> p = new ArrayList<>();
        if (deliveryWindow == null || attemptTimeout == null || initialBackoff == null || maxBackoff == null
                || leaseDuration == null || pollInterval == null || expiryScanInterval == null) {
            p.add("every duration property is required");
            return p;
        }
        if (deliveryWindow.compareTo(CommandDeliveryLimits.MIN_DELIVERY_WINDOW) < 0)
            p.add("delivery-window must be at least " + CommandDeliveryLimits.MIN_DELIVERY_WINDOW);
        if (deliveryWindow.compareTo(CommandDeliveryLimits.MAX_DELIVERY_WINDOW) > 0)
            p.add("delivery-window must not exceed " + CommandDeliveryLimits.MAX_DELIVERY_WINDOW
                    + " (firmware freshness " + CommandDeliveryLimits.FIRMWARE_FRESHNESS_WINDOW
                    + " minus margin " + CommandDeliveryLimits.CLOCK_UNCERTAINTY_MARGIN + ")");
        if (deliveryWindow.getNano() != 0)
            p.add("delivery-window must be a whole number of seconds (the payload timestamp has second precision)");
        if (maxAttempts < 1 || maxAttempts > CommandDeliveryLimits.MAX_ATTEMPTS_CEILING)
            p.add("max-attempts must be between 1 and " + CommandDeliveryLimits.MAX_ATTEMPTS_CEILING);
        if (attemptTimeout.isZero() || attemptTimeout.isNegative()) p.add("attempt-timeout must be positive");
        else if (attemptTimeout.compareTo(deliveryWindow) > 0) p.add("attempt-timeout must not exceed delivery-window");
        if (initialBackoff.isZero() || initialBackoff.isNegative()) p.add("initial-backoff must be positive");
        if (maxBackoff.compareTo(initialBackoff) < 0) p.add("max-backoff must be at least initial-backoff");
        if (Double.isNaN(backoffMultiplier) || backoffMultiplier < 1.0 || backoffMultiplier > 4.0) p.add("backoff-multiplier must be between 1.0 and 4.0");
        if (Double.isNaN(jitterFraction) || jitterFraction < 0.0 || jitterFraction > 0.5) p.add("jitter-fraction must be between 0.0 and 0.5");
        if (leaseDuration.compareTo(attemptTimeout.plus(CommandDeliveryLimits.LEASE_MARGIN)) < 0)
            p.add("lease-duration must be at least attempt-timeout plus " + CommandDeliveryLimits.LEASE_MARGIN);
        if (claimBatchSize < 1 || claimBatchSize > 100) p.add("claim-batch-size must be between 1 and 100");
        if (workers < 1 || workers > 16) p.add("workers must be between 1 and 16");
        if (pollInterval.compareTo(Duration.ofMillis(50)) < 0 || pollInterval.compareTo(Duration.ofSeconds(5)) > 0)
            p.add("poll-interval must be between 50 ms and 5 s");
        if (expiryScanInterval.compareTo(Duration.ofMillis(100)) < 0 || expiryScanInterval.compareTo(Duration.ofSeconds(10)) > 0)
            p.add("expiry-scan-interval must be between 100 ms and 10 s");
        // Only meaningful once the individual values are sane.
        if (p.isEmpty() && worstCaseSchedule().compareTo(deliveryWindow) > 0)
            p.add("worst-case retry schedule " + worstCaseSchedule() + " does not fit delivery-window " + deliveryWindow);
        return p;
    }

    /** Delay before attempt number {@code attemptNumber} (2 for the first retry), without jitter and capped. */
    public Duration baseBackoffBeforeAttempt(int attemptNumber) {
        if (attemptNumber < 2) return Duration.ZERO;
        double millis = initialBackoff.toMillis() * Math.pow(backoffMultiplier, attemptNumber - 2);
        return Duration.ofMillis((long) Math.min(millis, maxBackoff.toMillis()));
    }

    /** Longest possible delay before attempt {@code attemptNumber}: the capped backoff plus maximum jitter. */
    public Duration maxBackoffBeforeAttempt(int attemptNumber) {
        return Duration.ofMillis((long) Math.ceil(baseBackoffBeforeAttempt(attemptNumber).toMillis() * (1.0 + jitterFraction) - 1e-6));
    }

    /**
     * Worst case for a full retry sequence: every attempt runs to its timeout and every backoff is at its
     * jittered maximum. It must fit inside the delivery window.
     */
    public Duration worstCaseSchedule() {
        Duration total = attemptTimeout.multipliedBy(maxAttempts);
        for (int attempt = 2; attempt <= maxAttempts; attempt++) total = total.plus(maxBackoffBeforeAttempt(attempt));
        return total;
    }

    public boolean enabled() { return enabled; }
    public Duration deliveryWindow() { return deliveryWindow; }
    public int maxAttempts() { return maxAttempts; }
    public Duration attemptTimeout() { return attemptTimeout; }
    public Duration initialBackoff() { return initialBackoff; }
    public double backoffMultiplier() { return backoffMultiplier; }
    public Duration maxBackoff() { return maxBackoff; }
    public double jitterFraction() { return jitterFraction; }
    public Duration leaseDuration() { return leaseDuration; }
    public int claimBatchSize() { return claimBatchSize; }
    public Duration pollInterval() { return pollInterval; }
    public Duration expiryScanInterval() { return expiryScanInterval; }
    public int workers() { return workers; }
}
