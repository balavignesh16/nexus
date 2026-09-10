package com.nexus.twin.persistence;

import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.DigitalTwinRegistry;
import com.nexus.twin.domain.DigitalTwinRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryDigitalTwinRegistry implements DigitalTwinRegistry {
    private static final Logger log = LoggerFactory.getLogger(InMemoryDigitalTwinRegistry.class);

    private final ConcurrentMap<UUID, DigitalTwin> cache = new ConcurrentHashMap<>();
    private final DigitalTwinRepository repository;

    public InMemoryDigitalTwinRegistry(DigitalTwinRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        log.info("Hydrating Digital Twin cache from database...");
        List<DigitalTwin> twins = repository.findAll();
        for (DigitalTwin twin : twins) {
            cache.put(twin.deviceId(), twin);
        }
        log.info("Loaded {} twins into cache", twins.size());
    }

    @Override
    public void put(DigitalTwin twin) {
        // Authoritative Persistence First
        repository.save(twin);
        // Then update cache
        cache.put(twin.deviceId(), twin);
    }

    @Override
    public Optional<DigitalTwin> get(UUID deviceId) {
        // Cache read
        return Optional.ofNullable(cache.get(deviceId));
    }

    @Override
    public void remove(UUID deviceId) {
        repository.deleteById(deviceId);
        cache.remove(deviceId);
    }

    @Override
    public List<DigitalTwin> listAll() {
        return new ArrayList<>(cache.values());
    }
}
