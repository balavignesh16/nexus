package com.nexus.adapter.core;

import com.nexus.adapter.spi.Attempt;
import com.nexus.adapter.spi.RejectionCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The adapter meters of adapter-contract.md section 17. The tag keys are fixed (adapter, result, reason, version, outcome) and every tag
 * value comes from a closed set or an adapter id: never a device id, command id, payload or exception text.
 */
@Component
public class AdapterMetrics {
    private final MeterRegistry registry;

    public AdapterMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void received(String adapter, String result) {
        registry.counter("nexus.adapter.received", "adapter", adapter, "result", result).increment();
    }

    public void rejected(String adapter, RejectionCode reason) {
        registry.counter("nexus.adapter.rejected", "adapter", adapter, "reason", reason.name()).increment();
    }

    public void contract(String version) {
        registry.counter("nexus.adapter.contract", "version", version).increment();
    }

    public void egress(String adapter, Attempt.Outcome outcome) {
        registry.counter("nexus.adapter.egress", "adapter", adapter, "outcome", outcome.name()).increment();
    }

    public void authFailure(RejectionCode reason) {
        registry.counter("nexus.adapter.auth.failures", "reason", reason.name()).increment();
    }
}
