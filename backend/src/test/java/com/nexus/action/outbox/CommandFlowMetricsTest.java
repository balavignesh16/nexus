package com.nexus.action.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The flow-control metrics (contract R18, R20): fixed names, bounded tags (a scope and an origin, never an identifier), a cached and failure-tolerant gauge. */
class CommandFlowMetricsTest {

    private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("registry", registry);
        return bf.getBeanProvider(MeterRegistry.class);
    }

    @Test
    void aRefusalIsCountedByScopeAndOriginOnly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class))).thenReturn(3);
        when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(3);
        MeterRegistry registry = new SimpleMeterRegistry();
        CommandAdmission admission = new CommandAdmission(jdbc, new CommandFlowSettings(100, 3, 20), registry);
        UUID device = UUID.randomUUID();
        assertThrows(CommandQueueFullException.class, () -> admission.admit(device, false));
        assertThrows(CommandQueueFullException.class, () -> admission.admit(device, true));
        assertThrows(CommandQueueFullException.class, () -> admission.admit(device, true));
        assertEquals(1, registry.counter("nexus.command.flow.rejected", "scope", "DEVICE", "origin", "MANUAL").count());
        assertEquals(2, registry.counter("nexus.command.flow.rejected", "scope", "DEVICE", "origin", "RULE").count());
        registry.getMeters().stream().filter(m -> m.getId().getName().equals("nexus.command.flow.rejected")).forEach(m ->
                m.getId().getTags().forEach(t -> assertTrue(java.util.Set.of("scope", "origin").contains(t.getKey()) && !t.getValue().contains(device.toString()),
                        "no identifier is ever a tag: " + t)));
    }

    @Test
    void theQueueGaugesReportTheUnresolvedCountAndTheUtilizationAndAreReadAtMostOncePerSecond() {
        AtomicInteger reads = new AtomicInteger();
        CommandAdmission admission = new CommandAdmission(mock(JdbcTemplate.class), new CommandFlowSettings(200, 10, 20), (MeterRegistry) null) {
            @Override public int unresolvedCount() { reads.incrementAndGet(); return 50; }
        };
        MeterRegistry registry = new SimpleMeterRegistry();
        CommandFlowMetrics metrics = new CommandFlowMetrics(admission, new CommandFlowSettings(200, 10, 20), provider(registry));
        metrics.register();
        assertEquals(50.0, registry.get("nexus.command.queue.unresolved").gauge().value());
        assertEquals(0.25, registry.get("nexus.command.queue.utilization").gauge().value(), 1e-9);
        for (int i = 0; i < 50; i++) registry.get("nexus.command.queue.unresolved").gauge().value();
        assertEquals(1, reads.get(), "a burst of scrapes costs one database read");
        assertEquals(200.0, registry.get("nexus.command.flow.global-queue-cap").gauge().value());
        assertEquals(20.0, registry.get("nexus.command.flow.publish-rate-limit").gauge().value());
    }

    @Test
    void aDatabaseOutageMakesTheGaugeNaNInsteadOfFailingTheScrape() {
        CommandAdmission admission = new CommandAdmission(mock(JdbcTemplate.class), new CommandFlowSettings(200, 10, 20), (MeterRegistry) null) {
            @Override public int unresolvedCount() { throw new DataAccessResourceFailureException("database unreachable"); }
        };
        MeterRegistry registry = new SimpleMeterRegistry();
        CommandFlowMetrics metrics = new CommandFlowMetrics(admission, new CommandFlowSettings(200, 10, 20), provider(registry));
        metrics.register();
        assertTrue(Double.isNaN(registry.get("nexus.command.queue.unresolved").gauge().value()));
        assertNotNull(registry.find("nexus.command.queue.utilization").gauge());
        assertFalse(registry.getMeters().isEmpty());
    }
}
