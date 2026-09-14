package com.nexus.event.api;

import com.nexus.event.api.dto.EventResponse;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.DomainEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class EventController {

    private final DomainEventRepository eventRepository;

    public EventController(DomainEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /**
     * Optional deviceId/correlationId filters (Phase 3): a device's Events
     * tab must only ever show events actually recorded against that
     * device, and an investigation entry point must be able to pull every
     * event sharing a real correlationId - never every event in the
     * system. Both preserve the unfiltered default for existing callers.
     * At most one filter may be supplied at a time.
     */
    @GetMapping("/events")
    public ResponseEntity<List<EventResponse>> getEvents(
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "deviceId", required = false) UUID deviceId,
            @RequestParam(name = "correlationId", required = false) String correlationId) {
        if (correlationId != null && correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId, if supplied, must not be blank");
        }

        Page<DomainEvent> page;
        if (deviceId != null) {
            page = eventRepository.findByDeviceIdOrderByTimestampDesc(deviceId, PageRequest.of(0, limit));
        } else if (correlationId != null) {
            page = eventRepository.findByCorrelationIdOrderByTimestampDesc(correlationId, PageRequest.of(0, limit));
        } else {
            page = eventRepository.findAllByOrderByTimestampDesc(PageRequest.of(0, limit));
        }

        List<EventResponse> responses = page.stream()
                .map(EventResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }
}
