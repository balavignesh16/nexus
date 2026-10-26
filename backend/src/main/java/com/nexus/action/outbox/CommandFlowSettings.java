package com.nexus.action.outbox;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Flow-control limits of the command outbox (contract R18, ticket P4.6), validated when the application starts: an invalid value
 * throws, so the application refuses to start instead of running with an unbounded or contradictory limit.
 *
 * <ul>
 * <li>{@code nexus.command.flow.global-queue-cap} (default 500): the most unresolved delivery obligations (QUEUED, IN_FLIGHT and
 *     RETRY_WAIT, that is every command that has been accepted and has neither been published nor dead-lettered) that may exist at
 *     once. A new command is refused (manual) or suppressed (rule) when accepting it would exceed the cap. Enforced only when the
 *     outbox flag is on, because only then does acceptance create obligations.</li>
 * <li>{@code nexus.command.flow.device-queue-cap} (default 10): the same bound for one device, so one device cannot occupy the
 *     whole global capacity. Never above the global cap.</li>
 * <li>{@code nexus.command.flow.publish-rate-per-second} (default 20): the most publications the dispatchers of ALL backend instances
 *     together may have in flight or confirmed within any one second ({@link CommandPublishGate}). Applies to every claim, including
 *     retries and the draining of an outbox whose flag was switched off again.</li>
 * </ul>
 */
@Component
public class CommandFlowSettings {
    public static final int MAX_GLOBAL_QUEUE_CAP = 100_000;
    public static final int MAX_DEVICE_QUEUE_CAP = 1_000;
    public static final int MAX_PUBLISH_RATE = 1_000;

    private final int globalQueueCap;
    private final int deviceQueueCap;
    private final int publishRatePerSecond;

    @Autowired
    public CommandFlowSettings(@Value("${nexus.command.flow.global-queue-cap:500}") int globalQueueCap,
                               @Value("${nexus.command.flow.device-queue-cap:10}") int deviceQueueCap,
                               @Value("${nexus.command.flow.publish-rate-per-second:20}") int publishRatePerSecond) {
        this.globalQueueCap = globalQueueCap;
        this.deviceQueueCap = deviceQueueCap;
        this.publishRatePerSecond = publishRatePerSecond;
        List<String> problems = problems();
        if (!problems.isEmpty())
            throw new IllegalStateException("Invalid nexus.command.flow configuration: " + String.join("; ", problems));
    }

    /** All violated rules, empty when the configuration is valid. */
    public List<String> problems() {
        List<String> p = new ArrayList<>();
        if (globalQueueCap < 1 || globalQueueCap > MAX_GLOBAL_QUEUE_CAP) p.add("global-queue-cap must be between 1 and " + MAX_GLOBAL_QUEUE_CAP);
        if (deviceQueueCap < 1 || deviceQueueCap > MAX_DEVICE_QUEUE_CAP) p.add("device-queue-cap must be between 1 and " + MAX_DEVICE_QUEUE_CAP);
        if (deviceQueueCap > globalQueueCap) p.add("device-queue-cap must not exceed global-queue-cap");
        if (publishRatePerSecond < 1 || publishRatePerSecond > MAX_PUBLISH_RATE) p.add("publish-rate-per-second must be between 1 and " + MAX_PUBLISH_RATE);
        return p;
    }

    public int globalQueueCap() { return globalQueueCap; }
    public int deviceQueueCap() { return deviceQueueCap; }
    public int publishRatePerSecond() { return publishRatePerSecond; }
}
