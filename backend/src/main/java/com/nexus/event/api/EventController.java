package com.nexus.event.api;

import com.nexus.event.api.dto.EventResponse;
import com.nexus.event.domain.DomainEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class EventController {

    private final DomainEventRepository eventRepository;

    public EventController(DomainEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @GetMapping("/events")
    public ResponseEntity<List<EventResponse>> getEvents(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        List<EventResponse> responses = eventRepository.findAllByOrderByTimestampDesc(PageRequest.of(0, limit)).stream()
                .map(EventResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }
}
