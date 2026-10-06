package com.nexus.twin.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DesiredTwinStateRepository extends JpaRepository<DesiredTwinState, DesiredTwinStateId> {
    List<DesiredTwinState> findByDeviceId(UUID deviceId);
}
