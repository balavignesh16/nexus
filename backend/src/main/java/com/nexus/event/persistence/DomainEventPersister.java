package com.nexus.event.persistence;

import com.nexus.event.application.EventDispatcher;
import com.nexus.event.application.EventListener;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.DomainEventRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DomainEventPersister implements EventListener {
    private jakarta.persistence.EntityManager entities;
    @org.springframework.beans.factory.annotation.Autowired
    void setEntities(jakarta.persistence.EntityManager entities){this.entities=entities;}

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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEvent(DomainEvent event) {
        if(!repository.existsById(event.eventId()))repository.save(event);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public DomainEvent persistRequired(DomainEvent event) {
        // Concrete committed boundary, independent of subscriber ordering.
        if(!repository.existsById(event.eventId()))repository.saveAndFlush(event);
        if(entities!=null)entities.clear();
        DomainEvent persisted=repository.findById(event.eventId()).orElseThrow(()->new IllegalStateException("Required source fact is missing"));
        if(persisted.eventType()!=event.eventType() || !java.util.Objects.equals(persisted.deviceId(),event.deviceId())
            || !java.util.Objects.equals(persisted.correlationId(),event.correlationId()))throw new IllegalStateException("Source identity does not match persisted scope/type/correlation");
        // Use the saved fact, not a mutable or conflicting in-memory payload.
        return persisted;
    }
}
