package com.nexus.event.api;

import com.nexus.event.api.dto.EventResponse;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.DomainEventRepository;
import com.nexus.event.domain.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for EventController's device-scoped and correlation-scoped
 * history filters (Phase 3) - the same "belongs to this device" gap
 * already closed for /rules and /actions/history is closed here.
 */
class EventControllerTest {

    private DomainEventRepository eventRepository;
    private EventController controller;

    @BeforeEach
    void setUp() {
        eventRepository = mock(DomainEventRepository.class);
        controller = new EventController(eventRepository);
    }

    private DomainEvent event(UUID deviceId, String correlationId) {
        return new DomainEvent(UUID.randomUUID(), EventType.SENSOR_VALUE_CHANGED, deviceId, Instant.now(),
                "test", Map.of(), "INFO", correlationId);
    }

    @Test
    void getEvents_noFilters_usesFindAll() {
        when(eventRepository.findAllByOrderByTimestampDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event(UUID.randomUUID(), "corr-1"))));

        ResponseEntity<List<EventResponse>> response = controller.getEvents(100, null, null);

        assertEquals(1, response.getBody().size());
        verify(eventRepository).findAllByOrderByTimestampDesc(any(Pageable.class));
    }

    @Test
    void getEvents_deviceIdFilter_returnsOnlyThatDevicesEvents() {
        UUID deviceA = UUID.randomUUID();
        when(eventRepository.findByDeviceIdOrderByTimestampDesc(eq(deviceA), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event(deviceA, null))));

        ResponseEntity<List<EventResponse>> response = controller.getEvents(100, deviceA, null);

        assertEquals(1, response.getBody().size());
        assertEquals(deviceA, response.getBody().get(0).deviceId());
        verify(eventRepository, never()).findAllByOrderByTimestampDesc(any(Pageable.class));
    }

    @Test
    void getEvents_deviceIdWithNoMatches_returnsEmptyListNotError() {
        UUID unknownDevice = UUID.randomUUID();
        when(eventRepository.findByDeviceIdOrderByTimestampDesc(eq(unknownDevice), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<List<EventResponse>> response = controller.getEvents(100, unknownDevice, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, response.getBody().size());
    }

    @Test
    void getEvents_correlationIdFilter_returnsOnlyMatchingEvents() {
        when(eventRepository.findByCorrelationIdOrderByTimestampDesc(eq("corr-1"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event(UUID.randomUUID(), "corr-1"))));

        ResponseEntity<List<EventResponse>> response = controller.getEvents(100, null, "corr-1");

        assertEquals(1, response.getBody().size());
        assertEquals("corr-1", response.getBody().get(0).correlationId());
    }

    @Test
    void getEvents_blankCorrelationId_rejectedAsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> controller.getEvents(100, null, "   "));
    }

    @Test
    void getEvents_correlationIdIncludedInResponse_whenPresent() {
        when(eventRepository.findAllByOrderByTimestampDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event(UUID.randomUUID(), "corr-xyz"))));

        ResponseEntity<List<EventResponse>> response = controller.getEvents(100, null, null);

        assertEquals("corr-xyz", response.getBody().get(0).correlationId());
    }
}
