package com.nexus.adapter;

import com.nexus.adapter.core.AdapterMetrics;
import com.nexus.adapter.core.AdapterRegistry;
import com.nexus.adapter.core.CommandEgressRouter;
import com.nexus.adapter.core.TransportAssignments;
import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.Attempt;
import com.nexus.adapter.spi.CommandEgress;
import com.nexus.adapter.spi.CommandEnvelope;
import com.nexus.adapter.spi.EvidenceKind;
import com.nexus.adapter.spi.TelemetryDecoder;
import com.nexus.adapter.spi.TransportKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Registration rules, ingest-only adapters (P5-032), the lifecycle, and the egress router. */
class AdapterRegistryTest {

    private static CommandEnvelope command(UUID device) {
        return new CommandEnvelope(UUID.randomUUID(), device, "{}".getBytes(), "00", 1L, "c");
    }

    @Test
    void p5_032_anIngestOnlyAdapterIsRegisteredShownAsSuchAndNeverRoutedACommand() {
        FakeAdapters.IngestOnly http = new FakeAdapters.IngestOnly("http-webhook", TransportKind.HTTP_WEBHOOK);
        AdapterRegistry registry = new AdapterRegistry(List.of(http));
        var report = registry.reports().get(0);
        assertEquals("http-webhook", report.id());
        assertTrue(report.ingestOnly(), "no COMMAND_EGRESS capability: command egress is not supported");
        assertFalse(report.capabilities().contains(AdapterCapability.COMMAND_EGRESS));
        assertTrue(registry.capability(TransportKind.HTTP_WEBHOOK, TelemetryDecoder.class).isPresent());
        assertTrue(registry.capability(TransportKind.HTTP_WEBHOOK, CommandEgress.class).isEmpty());

        UUID device = UUID.randomUUID();
        TransportAssignments assignments = mock(TransportAssignments.class);
        when(assignments.assignedTo(device)).thenReturn(Optional.of(TransportKind.HTTP_WEBHOOK));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CommandEgressRouter router = new CommandEgressRouter(registry, assignments, new AdapterMetrics(meters));
        Attempt attempt = router.deliver(command(device), Duration.ofSeconds(1));
        assertEquals(Attempt.Outcome.NOT_SENT, attempt.outcome());
        assertEquals(CommandEgressRouter.EGRESS_NOT_SUPPORTED, attempt.code());
        assertEquals(0, http.decoded.get(), "nothing was handed to the adapter");
    }

    @Test
    void theRouterDeliversToTheAdapterOfTheDevicesAssignmentAndCountsTheOutcome() {
        FakeAdapters.FullAdapter mqtt = new FakeAdapters.FullAdapter("mqtt-json", TransportKind.MQTT_JSON);
        FakeAdapters.FullAdapter simulator = new FakeAdapters.FullAdapter("simulator", TransportKind.SIMULATOR);
        AdapterRegistry registry = new AdapterRegistry(List.of(mqtt, simulator));
        UUID device = UUID.randomUUID();
        TransportAssignments assignments = mock(TransportAssignments.class);
        when(assignments.assignedTo(device)).thenReturn(Optional.of(TransportKind.SIMULATOR));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CommandEgressRouter router = new CommandEgressRouter(registry, assignments, new AdapterMetrics(meters));
        assertEquals(Attempt.Outcome.CONFIRMED, router.deliver(command(device), Duration.ofSeconds(1)).outcome());
        assertEquals(1, simulator.deliveries.get());
        assertEquals(0, mqtt.deliveries.get(), "the assignment selects the adapter, nothing else does");
        assertEquals(1.0, meters.get("nexus.adapter.egress").tag("adapter", "simulator").tag("outcome", "CONFIRMED").counter().count());

        UUID unknown = UUID.randomUUID();
        when(assignments.assignedTo(unknown)).thenReturn(Optional.empty());
        assertEquals(CommandEgressRouter.DEVICE_UNKNOWN, router.deliver(command(unknown), Duration.ofSeconds(1)).code());
    }

    @Test
    void anAdapterThatThrowsIsClassifiedUncertainNeverNotSent() {
        FakeAdapters.FullAdapter mqtt = new FakeAdapters.FullAdapter("mqtt-json", TransportKind.MQTT_JSON);
        mqtt.behaviour = c -> { throw new IllegalStateException("socket reset after write"); };
        AdapterRegistry registry = new AdapterRegistry(List.of(mqtt));
        UUID device = UUID.randomUUID();
        TransportAssignments assignments = mock(TransportAssignments.class);
        when(assignments.assignedTo(device)).thenReturn(Optional.of(TransportKind.MQTT_JSON));
        Attempt attempt = new CommandEgressRouter(registry, assignments, new AdapterMetrics(new SimpleMeterRegistry())).deliver(command(device), Duration.ofSeconds(1));
        assertEquals(Attempt.Outcome.UNCERTAIN, attempt.outcome());
        assertEquals(CommandEgressRouter.ADAPTER_FAILED, attempt.code());
    }

    @Test
    void aDeclarationWithoutCodeOrCodeWithoutADeclarationStopsStartup() {
        assertTrue(assertThrows(IllegalStateException.class, () -> new AdapterRegistry(List.of(new FakeAdapters.LiarAdapter()))).getMessage().contains("without implementing"));
        assertTrue(assertThrows(IllegalStateException.class, () -> new AdapterRegistry(List.of(new FakeAdapters.ShyAdapter()))).getMessage().contains("does not declare"));
    }

    @Test
    void idsAndTransportsAreUnique() {
        assertThrows(IllegalStateException.class, () -> new AdapterRegistry(List.of(
                new FakeAdapters.IngestOnly("a", TransportKind.HTTP_WEBHOOK), new FakeAdapters.IngestOnly("a", TransportKind.SIMULATOR))));
        assertThrows(IllegalStateException.class, () -> new AdapterRegistry(List.of(
                new FakeAdapters.IngestOnly("a", TransportKind.HTTP_WEBHOOK), new FakeAdapters.IngestOnly("b", TransportKind.HTTP_WEBHOOK))));
        assertTrue(new AdapterRegistry(List.<com.nexus.adapter.spi.AdapterDescriptor>of()).reports().isEmpty(), "an application with no adapter starts");
    }

    @Test
    void theLifecycleStartsAndStopsOnlyAdaptersThatOwnAConnection() {
        FakeAdapters.FullAdapter full = new FakeAdapters.FullAdapter("simulator", TransportKind.SIMULATOR);
        FakeAdapters.IngestOnly passive = new FakeAdapters.IngestOnly("http-webhook", TransportKind.HTTP_WEBHOOK);
        AdapterRegistry registry = new AdapterRegistry(List.of(full, passive));
        assertFalse(registry.isRunning());
        registry.start();
        assertTrue(registry.isRunning());
        assertEquals(1, full.starts.get());
        assertTrue(registry.reports().stream().filter(r -> r.id().equals("simulator")).findFirst().orElseThrow().running());
        registry.stop();
        assertEquals(1, full.stops.get());
        assertFalse(registry.reports().stream().filter(r -> r.id().equals("simulator")).findFirst().orElseThrow().running());
    }

    @Test
    void reportsCarryOnlyCountersAndAreSortedById() {
        AdapterRegistry registry = new AdapterRegistry(List.of(new FakeAdapters.IngestOnly("zeta", TransportKind.HTTP_WEBHOOK),
                new FakeAdapters.FullAdapter("alpha", TransportKind.SIMULATOR)));
        assertEquals(List.of("alpha", "zeta"), registry.reports().stream().map(AdapterRegistry.Report::id).toList());
        registry.reports().forEach(r -> r.counters().values().forEach(v -> assertTrue(v >= 0)));
        assertEquals(EvidenceKind.values().length, 3);
    }
}
