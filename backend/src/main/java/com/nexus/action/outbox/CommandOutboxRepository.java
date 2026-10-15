package com.nexus.action.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.UUID;

/**
 * Read and create access only in P4.1. The claim, lease, outcome and sweep queries belong to the dispatcher slice.
 */
public interface CommandOutboxRepository extends JpaRepository<CommandOutboxEntity, UUID> {
    long countByState(OutboxState state);

    boolean existsByDeviceId(UUID deviceId);

    boolean existsByDeviceIdAndStateIn(UUID deviceId, Collection<OutboxState> states);
}
