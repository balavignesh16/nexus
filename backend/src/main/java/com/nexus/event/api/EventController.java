package com.nexus.event.api;

import com.nexus.event.domain.DomainEvent;
import com.nexus.event.persistence.BoundedEventStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class EventController {

    private final BoundedEventStore eventStore;

    public EventController(BoundedEventStore eventStore) {
        this.eventStore = eventStore;
    }

    @GetMapping("/events")
    public ResponseEntity<List<DomainEvent>> getEvents(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return ResponseEntity.ok(eventStore.getRecentEvents(limit));
    }
}
