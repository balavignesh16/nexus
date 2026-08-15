package com.nexus.event.application;

import com.nexus.event.domain.DomainEvent;
import com.nexus.realtime.RealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final List<EventListener> listeners = new CopyOnWriteArrayList<>();
    private final RealtimePublisher realtimePublisher;

    public EventDispatcher(RealtimePublisher realtimePublisher) {
        this.realtimePublisher = realtimePublisher;
    }

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

        // Broadcast to WebSocket clients
        realtimePublisher.publishEvent(event, event.correlationId());

        for (EventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.error("Error dispatching event {} to listener {}", event.eventType(), listener.getClass().getSimpleName(), e);
            }
        }
    }
}
