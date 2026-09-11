package com.nexus.event.application;

import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.realtime.RealtimePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests EventDispatcher's ordering guarantee: all subscribed listeners
 * (including DomainEventPersister, which persists the event to the database)
 * must run to completion before the event is broadcast over WebSocket, so a
 * client reacting to a realtime push can trust the event is already durable.
 */
class EventDispatcherTest {

    private RealtimePublisher realtimePublisher;
    private EventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        realtimePublisher = mock(RealtimePublisher.class);
        dispatcher = new EventDispatcher(realtimePublisher);
    }

    private DomainEvent sampleEvent(String correlationId) {
        return new DomainEvent(UUID.randomUUID(), EventType.TWIN_UPDATED, UUID.randomUUID(),
                Instant.now(), "test", Map.of(), "INFO", correlationId);
    }

    @Test
    void publish_persistenceListenerRunsBeforeRealtimeBroadcast() {
        EventListener persister = mock(EventListener.class, "persister");
        dispatcher.subscribe(persister);

        DomainEvent event = sampleEvent("corr-1");
        dispatcher.publish(event);

        InOrder inOrder = inOrder(persister, realtimePublisher);
        inOrder.verify(persister).onEvent(event);
        inOrder.verify(realtimePublisher).publishEvent(event, "corr-1");
    }

    @Test
    void publish_allListenersRunBeforeBroadcast_evenWithMultipleListeners() {
        EventListener persister = mock(EventListener.class, "persister");
        EventListener ruleMatcher = mock(EventListener.class, "ruleMatcher");
        dispatcher.subscribe(persister);
        dispatcher.subscribe(ruleMatcher);

        DomainEvent event = sampleEvent("corr-2");
        dispatcher.publish(event);

        InOrder inOrder = inOrder(persister, ruleMatcher, realtimePublisher);
        inOrder.verify(persister).onEvent(event);
        inOrder.verify(ruleMatcher).onEvent(event);
        inOrder.verify(realtimePublisher).publishEvent(event, "corr-2");
    }

    @Test
    void publish_listenerThrows_otherListenersAndBroadcastStillRun() {
        EventListener failingListener = mock(EventListener.class, "failing");
        EventListener healthyListener = mock(EventListener.class, "healthy");
        doThrow(new RuntimeException("simulated persistence failure")).when(failingListener).onEvent(any());
        dispatcher.subscribe(failingListener);
        dispatcher.subscribe(healthyListener);

        DomainEvent event = sampleEvent("corr-3");

        assertDoesNotThrow(() -> dispatcher.publish(event));

        verify(healthyListener).onEvent(event);
        verify(realtimePublisher).publishEvent(event, "corr-3");
    }

    @Test
    void publish_noListeners_stillBroadcasts() {
        DomainEvent event = sampleEvent("corr-4");

        dispatcher.publish(event);

        verify(realtimePublisher).publishEvent(event, "corr-4");
    }
}
