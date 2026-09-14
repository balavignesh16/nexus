package com.nexus.event.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DomainEventRepository extends JpaRepository<DomainEvent, UUID> {
    Page<DomainEvent> findAllByOrderByTimestampDesc(Pageable pageable);

    // Phase 3: device-scoped and correlation-scoped event history, mirroring
    // the same device-isolation pattern already established for
    // /rules and /actions/history.
    Page<DomainEvent> findByDeviceIdOrderByTimestampDesc(UUID deviceId, Pageable pageable);

    Page<DomainEvent> findByCorrelationIdOrderByTimestampDesc(String correlationId, Pageable pageable);
}
