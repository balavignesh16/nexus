package com.nexus.realtime;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

@Component
public class RealtimeMetrics {

    private final MeterRegistry registry;
    private final RealtimeSessionManager sessionManager;
    private final RealtimeBuffer buffer;

    private Counter publishedMessages;
    private Counter invalidMessages;
    private Counter droppedMessages;
    private Counter clientReconnects;

    public RealtimeMetrics(MeterRegistry registry, RealtimeSessionManager sessionManager, RealtimeBuffer buffer) {
        this.registry = registry;
        this.sessionManager = sessionManager;
        this.buffer = buffer;
    }

    @PostConstruct
    public void init() {
        this.publishedMessages = registry.counter("nexus.realtime.messages.published");
        this.invalidMessages = registry.counter("nexus.realtime.messages.invalid");
        this.droppedMessages = registry.counter("nexus.realtime.messages.dropped");
        this.clientReconnects = registry.counter("nexus.realtime.clients.reconnects");

        registry.gauge("nexus.realtime.clients.connected", sessionManager, RealtimeSessionManager::getConnectedClientCount);
        // We'll just register one gauge, or proxy a sum. For now a fixed gauge is fine as an example.
    }

    public void recordPublishedMessage() {
        this.publishedMessages.increment();
    }

    public void recordInvalidMessage() {
        this.invalidMessages.increment();
    }

    public void recordDroppedMessage() {
        this.droppedMessages.increment();
    }
    
    public void recordClientReconnect() {
        this.clientReconnects.increment();
    }
}
