package com.nexus.event.application;

import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    // Using a simple list for all events for now, can be optimized per event type later if needed
    private final List<EventListener> listeners = new CopyOnWriteArrayList<>();

    public void subscribe(EventListener listener) {
        listeners.add(listener);
    }

    public void unregister(EventListener listener) {
        listeners.remove(listener);
    }

    public void publish(List<DomainEvent> events) {
        for (DomainEvent event : events) {
            publish(event);
        }
    }

    public void publish(DomainEvent event) {
        log.debug("Dispatching event: {} for device: {}", event.eventType(), event.deviceId());
        for (EventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.error("Error dispatching event {} to listener {}", event.eventType(), listener.getClass().getSimpleName(), e);
            }
        }
    }
}
