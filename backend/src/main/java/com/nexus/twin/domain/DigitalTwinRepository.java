package com.nexus.twin.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DigitalTwinRepository extends JpaRepository<DigitalTwin, DigitalTwinId> {
    List<DigitalTwin> findByDeviceId(UUID deviceId);
    java.util.Optional<DigitalTwin> findByDeviceIdAndStateKey(UUID deviceId, String stateKey);
}
