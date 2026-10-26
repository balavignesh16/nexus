package com.nexus.action.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Gauges for the command queue (contract R18, R20): how many obligations are unresolved and how much of the global cap that is. The value is
 * read from the database at most once per second no matter how often it is scraped, and a failed read yields NaN instead of an exception.
 * Fixed names and no identifiers as tags, so the cardinality is bounded. The counters (rejections by scope and origin, rate-limit deferrals,
 * commands that expired while waiting, publications) are incremented where the events happen.
 */
@Component
public class CommandFlowMetrics {
    private static final long CACHE_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final CommandAdmission admission;
    private final CommandFlowSettings settings;
    private final ObjectProvider<MeterRegistry> registry;
    private volatile double cached = Double.NaN;
    private volatile long cachedAt = System.nanoTime() - CACHE_NANOS * 2;

    @Autowired
    public CommandFlowMetrics(CommandAdmission admission, CommandFlowSettings settings, ObjectProvider<MeterRegistry> registry) {
        this.admission = admission;
        this.settings = settings;
        this.registry = registry;
    }

    @PostConstruct
    void register() {
        MeterRegistry meters = registry.getIfAvailable();
        if (meters == null) return;
        Gauge.builder("nexus.command.queue.unresolved", this, CommandFlowMetrics::unresolved)
                .description("Unresolved delivery obligations (QUEUED, IN_FLIGHT, RETRY_WAIT)").register(meters);
        Gauge.builder("nexus.command.queue.utilization", this, m -> m.unresolved() / m.settings.globalQueueCap())
                .description("Unresolved obligations as a fraction of the global queue cap").register(meters);
        Gauge.builder("nexus.command.flow.global-queue-cap", settings, CommandFlowSettings::globalQueueCap).register(meters);
        Gauge.builder("nexus.command.flow.publish-rate-limit", settings, CommandFlowSettings::publishRatePerSecond).register(meters);
    }

    double unresolved() {
        long now = System.nanoTime();
        if (now - cachedAt < CACHE_NANOS) return cached;
        try {
            cached = admission.unresolvedCount();
        } catch (RuntimeException databaseUnavailable) {
            cached = Double.NaN;
        }
        cachedAt = now;
        return cached;
    }
}
