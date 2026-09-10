package com.nexus.twin.application;

import com.nexus.pipeline.application.PipelineService;
import com.nexus.realtime.RealtimePublisher;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.DigitalTwinRegistry;
import com.nexus.twin.domain.TwinUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for DigitalTwinService's update-decision logic (out-of-order
 * protection) and its delegation to DigitalTwinRegistry. The registry itself is
 * mocked here; real persistence/hydration behavior is covered separately in
 * InMemoryDigitalTwinRegistryTest against a real database.
 */
class DigitalTwinServiceTest {

    private DigitalTwinRegistry registry;
    private ApplicationEventPublisher eventPublisher;
    private RealtimePublisher realtimePublisher;
    private PipelineService pipelineService;
    private DigitalTwinService service;

    @BeforeEach
    void setUp() {
        registry = mock(DigitalTwinRegistry.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        realtimePublisher = mock(RealtimePublisher.class);
        pipelineService = mock(PipelineService.class);
        service = new DigitalTwinService(registry, eventPublisher, realtimePublisher, pipelineService);
    }

    @Test
    void updateTwin_noExistingTwin_createsAndPersistsNewTwin() {
        UUID deviceId = UUID.randomUUID();
        Instant timestamp = Instant.now();
        when(registry.get(deviceId)).thenReturn(Optional.empty());

        TelemetryRequest request = new TelemetryRequest(deviceId, timestamp, "TEMPERATURE_SENSOR", 22.5, "CELSIUS");

        service.updateTwin(request, "corr-1");

        ArgumentCaptor<DigitalTwin> twinCaptor = ArgumentCaptor.forClass(DigitalTwin.class);
        verify(registry).put(twinCaptor.capture());
        DigitalTwin persisted = twinCaptor.getValue();
        assertEquals(deviceId, persisted.deviceId());
        assertEquals(timestamp, persisted.latestTimestamp());
        assertEquals("TEMPERATURE_SENSOR", persisted.latestSensorType());
        assertEquals(22.5, persisted.latestValue());
        assertEquals("CELSIUS", persisted.latestUnit());
        assertTrue(persisted.metadata().isEmpty(), "current implementation always writes empty metadata on update");

        ArgumentCaptor<TwinUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(TwinUpdatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        TwinUpdatedEvent published = eventCaptor.getValue();
        assertNull(published.previousState(), "no prior twin existed, so previousState must be null");
        assertEquals(persisted, published.currentState());
        assertEquals("corr-1", published.correlationId());

        verify(realtimePublisher).publishTwinDelta(isNull(), eq(persisted), eq("corr-1"));
        verify(pipelineService).recordTrace(any());
    }

    @Test
    void updateTwin_newerTimestamp_updatesTwinAndPreservesPreviousState() {
        UUID deviceId = UUID.randomUUID();
        Instant earlier = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant later = Instant.now();

        DigitalTwin existing = new DigitalTwin(deviceId, earlier, "TEMPERATURE_SENSOR", 20.0, "CELSIUS", earlier, java.util.Map.of());
        when(registry.get(deviceId)).thenReturn(Optional.of(existing));

        TelemetryRequest request = new TelemetryRequest(deviceId, later, "TEMPERATURE_SENSOR", 25.0, "CELSIUS");

        service.updateTwin(request, "corr-2");

        ArgumentCaptor<DigitalTwin> twinCaptor = ArgumentCaptor.forClass(DigitalTwin.class);
        verify(registry).put(twinCaptor.capture());
        assertEquals(25.0, twinCaptor.getValue().latestValue());
        assertEquals(later, twinCaptor.getValue().latestTimestamp());

        ArgumentCaptor<TwinUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(TwinUpdatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(existing, eventCaptor.getValue().previousState());
        assertEquals(twinCaptor.getValue(), eventCaptor.getValue().currentState());
    }

    @Test
    void updateTwin_olderTimestamp_isIgnoredEntirely() {
        UUID deviceId = UUID.randomUUID();
        Instant current = Instant.now();
        Instant older = current.minus(1, ChronoUnit.MINUTES);

        DigitalTwin existing = new DigitalTwin(deviceId, current, "TEMPERATURE_SENSOR", 20.0, "CELSIUS", current, java.util.Map.of());
        when(registry.get(deviceId)).thenReturn(Optional.of(existing));

        TelemetryRequest staleRequest = new TelemetryRequest(deviceId, older, "TEMPERATURE_SENSOR", 99.0, "CELSIUS");

        service.updateTwin(staleRequest, "corr-3");

        verify(registry, never()).put(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(realtimePublisher, never()).publishTwinDelta(any(), any(), any());
        verify(pipelineService, never()).recordTrace(any());
    }

    @Test
    void updateTwin_exactlyEqualTimestamp_isIgnored_boundaryCase() {
        // isAfter() is strict; a message with the SAME timestamp as the current
        // twin must not be treated as newer.
        UUID deviceId = UUID.randomUUID();
        Instant timestamp = Instant.now();

        DigitalTwin existing = new DigitalTwin(deviceId, timestamp, "TEMPERATURE_SENSOR", 20.0, "CELSIUS", timestamp, java.util.Map.of());
        when(registry.get(deviceId)).thenReturn(Optional.of(existing));

        TelemetryRequest sameTimestampRequest = new TelemetryRequest(deviceId, timestamp, "TEMPERATURE_SENSOR", 50.0, "CELSIUS");

        service.updateTwin(sameTimestampRequest, "corr-4");

        verify(registry, never()).put(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void getTwin_delegatesToRegistry() {
        UUID deviceId = UUID.randomUUID();
        DigitalTwin twin = new DigitalTwin(deviceId, Instant.now(), "TEMPERATURE_SENSOR", 21.0, "CELSIUS", Instant.now(), java.util.Map.of());
        when(registry.get(deviceId)).thenReturn(Optional.of(twin));

        Optional<DigitalTwin> result = service.getTwin(deviceId);

        assertTrue(result.isPresent());
        assertEquals(twin, result.get());
    }

    @Test
    void getTwin_unknownDevice_returnsEmpty() {
        UUID deviceId = UUID.randomUUID();
        when(registry.get(deviceId)).thenReturn(Optional.empty());

        assertTrue(service.getTwin(deviceId).isEmpty());
    }

    @Test
    void getAllTwins_delegatesToRegistryListAll() {
        DigitalTwin twin1 = new DigitalTwin(UUID.randomUUID(), Instant.now(), "TEMPERATURE_SENSOR", 1.0, "CELSIUS", Instant.now(), java.util.Map.of());
        DigitalTwin twin2 = new DigitalTwin(UUID.randomUUID(), Instant.now(), "GAS_SENSOR", 2.0, "PPM", Instant.now(), java.util.Map.of());
        when(registry.listAll()).thenReturn(List.of(twin1, twin2));

        List<DigitalTwin> result = service.getAllTwins();

        assertEquals(2, result.size());
        assertTrue(result.containsAll(List.of(twin1, twin2)));
    }
}
