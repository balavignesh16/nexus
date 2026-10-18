package com.nexus.adapter.core;

import com.nexus.adapter.spi.Eligibility;
import com.nexus.adapter.spi.TransportEligibility;
import com.nexus.adapter.spi.TransportKind;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@link TransportEligibility} the command acceptance guard calls: it picks the rule of the device's server-side transport. A device
 * assigned to a transport that has no registered rule (no adapter) is refused, never allowed: fail closed.
 */
@Component
@Primary
public class TransportEligibilityRouter implements TransportEligibility {
    public static final String NO_ADAPTER = "NO_ADAPTER";

    private final TransportAssignments assignments;
    private final Map<TransportKind, PerTransportEligibility> rules = new EnumMap<>(TransportKind.class);

    public TransportEligibilityRouter(TransportAssignments assignments, List<PerTransportEligibility> perTransport) {
        this.assignments = assignments;
        for (PerTransportEligibility rule : perTransport) {
            if (rules.put(rule.transport(), rule) != null) throw new IllegalStateException("Two eligibility rules for " + rule.transport());
        }
    }

    @Override
    public Eligibility eligible(UUID deviceId, Instant at) {
        Optional<TransportKind> assigned = assignments.assignedTo(deviceId);
        if (assigned.isEmpty()) return Eligibility.no("DEVICE_UNKNOWN", "Device not found: " + deviceId);
        PerTransportEligibility rule = rules.get(assigned.get());
        if (rule == null) return Eligibility.no(NO_ADAPTER, "No adapter is registered for transport " + assigned.get());
        return rule.eligible(deviceId, at);
    }
}
