package com.nexus.action.outbox;

import java.time.Duration;

/**
 * Hard bounds of command delivery (contract R11, R12). They are constants, not configuration: configuration is
 * validated against them at startup and the same ceilings are enforced again by the V37 check constraints.
 *
 * The committed firmware (firmware/src/main.cpp, COMMAND_STALENESS_WINDOW_SECONDS) rejects a command as STALE when
 * the ABSOLUTE difference between its own clock and the command timestamp exceeds 30 seconds, so the ceiling on
 * the delivery window is that bound minus a margin for everything the backend cannot control.
 */
public final class CommandDeliveryLimits {
    private CommandDeliveryLimits() {}

    /** Firmware freshness check (both directions). Read from firmware/src/main.cpp; the firmware is not modified. */
    public static final Duration FIRMWARE_FRESHNESS_WINDOW = Duration.ofSeconds(30);

    /**
     * Budget for what eats into the freshness check: epoch-second truncation of the payload timestamp (up to 1 s),
     * NTP clock error between backend and device in either direction (5 s), broker and network delay (2 s) and
     * a safety allowance (2 s).
     */
    public static final Duration TIMESTAMP_PRECISION_BUDGET = Duration.ofSeconds(1);
    public static final Duration CLOCK_SKEW_BUDGET = Duration.ofSeconds(5);
    public static final Duration NETWORK_DELAY_BUDGET = Duration.ofSeconds(2);
    public static final Duration SAFETY_ALLOWANCE = Duration.ofSeconds(2);
    public static final Duration CLOCK_UNCERTAINTY_MARGIN = TIMESTAMP_PRECISION_BUDGET
            .plus(CLOCK_SKEW_BUDGET).plus(NETWORK_DELAY_BUDGET).plus(SAFETY_ALLOWANCE);

    /** 30 s minus the 10 s margin = 20 s. */
    public static final Duration MAX_DELIVERY_WINDOW = FIRMWARE_FRESHNESS_WINDOW.minus(CLOCK_UNCERTAINTY_MARGIN);
    public static final Duration MIN_DELIVERY_WINDOW = Duration.ofSeconds(2);

    public static final int MAX_ATTEMPTS_CEILING = 5;
    /** The lease must outlast one attempt by at least this much so a slow but live attempt is not stolen. */
    public static final Duration LEASE_MARGIN = Duration.ofSeconds(2);

    /**
     * After a delivery outcome that is UNCERTAIN (the bytes may already be on the wire, or still in the MQTT client's
     * in-flight state and resent on reconnect), later commands of the same device are held for this long, counted from the
     * uncertain command's payload timestamp. The committed firmware applies any command whose timestamp differs from its own
     * clock by at most {@link #FIRMWARE_FRESHNESS_WINDOW} (in both directions) and has no commandId deduplication, so a late
     * copy can still be applied until the timestamp is older than that window; the margin covers timestamp truncation,
     * clock error between backend and device, and network delay. Afterwards the firmware rejects the message as STALE.
     * An ACK does NOT shorten the hold: a duplicate delivery of the same message would still be applied.
     */
    public static final Duration UNCERTAIN_DELIVERY_HOLD = FIRMWARE_FRESHNESS_WINDOW.plus(CLOCK_UNCERTAINTY_MARGIN);

    /**
     * Upper bound of the hold measured on the DATABASE clock from the uncertain command's creation, so a payload timestamp
     * produced by a wrong clock can never hold a device queue for longer than this.
     */
    public static final Duration UNCERTAIN_DELIVERY_HOLD_CAP = UNCERTAIN_DELIVERY_HOLD.plus(MAX_DELIVERY_WINDOW);
}
