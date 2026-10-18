package com.nexus.adapter.core;

import com.nexus.adapter.spi.Attempt;
import com.nexus.adapter.spi.CommandEgress;
import com.nexus.adapter.spi.CommandEnvelope;
import com.nexus.adapter.spi.TransportKind;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Routes ONE already-claimed command to the adapter of the device's server-side transport assignment and returns the adapter's
 * classified {@link Attempt}. It decides nothing about acceptance, admission, claims, leases or retries (those stay in Phase 4). It
 * refuses to route to an adapter that does not declare the {@link CommandEgress} capability, and it classifies an adapter that throws as
 * UNCERTAIN: once control has passed to a transport, an exception does not prove that nothing was sent.
 */
@Component
public class CommandEgressRouter {
    public static final String EGRESS_NOT_SUPPORTED = "EGRESS_NOT_SUPPORTED";
    public static final String DEVICE_UNKNOWN = "DEVICE_UNKNOWN";
    public static final String ADAPTER_FAILED = "ADAPTER_FAILED";

    private final AdapterRegistry registry;
    private final TransportAssignments assignments;
    private final AdapterMetrics metrics;

    public CommandEgressRouter(AdapterRegistry registry, TransportAssignments assignments, AdapterMetrics metrics) {
        this.registry = registry;
        this.assignments = assignments;
        this.metrics = metrics;
    }

    public Attempt deliver(CommandEnvelope command, Duration timeout) {
        Optional<TransportKind> assigned = assignments.assignedTo(command.deviceId());
        if (assigned.isEmpty()) return Attempt.notSent(DEVICE_UNKNOWN);
        Optional<CommandEgress> egress = registry.capability(assigned.get(), CommandEgress.class);
        if (egress.isEmpty()) return Attempt.notSent(EGRESS_NOT_SUPPORTED);
        String adapter = registry.forTransport(assigned.get()).orElseThrow().id();
        Attempt attempt;
        try {
            attempt = egress.get().deliver(command, timeout);
        } catch (RuntimeException e) {
            attempt = Attempt.uncertain(ADAPTER_FAILED);
        }
        metrics.egress(adapter, attempt.outcome());
        return attempt;
    }
}
