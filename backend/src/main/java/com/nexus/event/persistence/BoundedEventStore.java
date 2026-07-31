package com.nexus.event.persistence;

import com.nexus.event.application.EventDispatcher;
import com.nexus.event.application.EventListener;
import com.nexus.event.domain.DomainEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class BoundedEventStore implements EventListener {

    private static final int MAX_SIZE = 1000;
    private final ConcurrentLinkedDeque<DomainEvent> events = new ConcurrentLinkedDeque<>();
    private final EventDispatcher eventDispatcher;

    public BoundedEventStore(EventDispatcher eventDispatcher) {
        this.eventDispatcher = eventDispatcher;
    }

    @PostConstruct
    public void init() {
        eventDispatcher.subscribe(this);
    }

    @PreDestroy
    public void cleanup() {
        eventDispatcher.unregister(this);
    }

    @Override
    public void onEvent(DomainEvent event) {
        events.addFirst(event); // newest first
        if (events.size() > MAX_SIZE) {
            events.pollLast(); // remove oldest
        }
    }

    public List<DomainEvent> getRecentEvents(int limit) {
        int actualLimit = Math.min(limit, events.size());
        List<DomainEvent> result = new ArrayList<>(actualLimit);
        int count = 0;
        for (DomainEvent event : events) {
            if (count >= actualLimit) {
                break;
            }
            result.add(event);
            count++;
        }
        return result;
    }
}
