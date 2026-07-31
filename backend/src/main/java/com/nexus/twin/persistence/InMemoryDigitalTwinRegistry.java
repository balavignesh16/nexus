package com.nexus.twin.persistence;

import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.DigitalTwinRegistry;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryDigitalTwinRegistry implements DigitalTwinRegistry {

    private final ConcurrentMap<UUID, DigitalTwin> registry = new ConcurrentHashMap<>();

    @Override
    public void put(DigitalTwin twin) {
        registry.put(twin.deviceId(), twin);
    }

    @Override
    public Optional<DigitalTwin> get(UUID deviceId) {
        return Optional.ofNullable(registry.get(deviceId));
    }

    @Override
    public void remove(UUID deviceId) {
        registry.remove(deviceId);
    }

    @Override
    public List<DigitalTwin> listAll() {
        return new ArrayList<>(registry.values());
    }
}
