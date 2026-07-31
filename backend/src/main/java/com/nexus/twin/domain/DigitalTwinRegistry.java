package com.nexus.twin.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DigitalTwinRegistry {
    void put(DigitalTwin twin);
    Optional<DigitalTwin> get(UUID deviceId);
    void remove(UUID deviceId);
    List<DigitalTwin> listAll();
}
