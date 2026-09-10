package com.nexus.event.persistence;

import com.nexus.event.application.EventDispatcher;
import com.nexus.event.application.EventListener;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.DomainEventRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

@Component
public class DomainEventPersister implements EventListener {

    private final DomainEventRepository repository;
    private final EventDispatcher eventDispatcher;

    public DomainEventPersister(DomainEventRepository repository, EventDispatcher eventDispatcher) {
        this.repository = repository;
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
        repository.save(event);
    }
}
