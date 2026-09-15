package com.nexus.twin.persistence;

import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.DigitalTwinId;
import com.nexus.twin.domain.DigitalTwinRegistry;
import com.nexus.twin.domain.DigitalTwinRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryDigitalTwinRegistry implements DigitalTwinRegistry {
    private static final Logger log = LoggerFactory.getLogger(InMemoryDigitalTwinRegistry.class);

    // Phase 11: keyed by (deviceId, sensorType) - one cache entry per real
    // capability reading, not one per device. Small, bounded key count
    // (same performance assumption already accepted for the prior
    // per-device cache: device/capability counts are bounded by the
    // Site/Building/Space hierarchy, not by telemetry volume).
    private final ConcurrentMap<DigitalTwinId, DigitalTwin> cache = new ConcurrentHashMap<>();
    private final DigitalTwinRepository repository;

    public InMemoryDigitalTwinRegistry(DigitalTwinRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        log.info("Hydrating Digital Twin cache from database...");
        List<DigitalTwin> twins = repository.findAll();
        for (DigitalTwin twin : twins) {
            cache.put(new DigitalTwinId(twin.deviceId(), twin.latestSensorType()), twin);
        }
        log.info("Loaded {} twins into cache", twins.size());
    }

    @Override
    public void put(DigitalTwin twin) {
        // Authoritative Persistence First
        repository.save(twin);
        // Then update cache
        cache.put(new DigitalTwinId(twin.deviceId(), twin.latestSensorType()), twin);
    }

    @Override
    public Optional<DigitalTwin> get(UUID deviceId) {
        return getAllForDevice(deviceId).stream()
                .max(Comparator.comparing(DigitalTwin::latestTimestamp));
    }

    @Override
    public Optional<DigitalTwin> get(UUID deviceId, String sensorType) {
        return Optional.ofNullable(cache.get(new DigitalTwinId(deviceId, sensorType)));
    }

    @Override
    public List<DigitalTwin> getAllForDevice(UUID deviceId) {
        List<DigitalTwin> result = new ArrayList<>();
        for (DigitalTwin twin : cache.values()) {
            if (twin.deviceId().equals(deviceId)) {
                result.add(twin);
            }
        }
        return result;
    }

    @Override
    public void remove(UUID deviceId) {
        List<DigitalTwin> toRemove = getAllForDevice(deviceId);
        for (DigitalTwin twin : toRemove) {
            DigitalTwinId id = new DigitalTwinId(twin.deviceId(), twin.latestSensorType());
            repository.deleteById(id);
            cache.remove(id);
        }
    }

    @Override
    public List<DigitalTwin> listAll() {
        // One representative (most-recently-updated) row per device -
        // matches the existing GET /twins contract exactly, never one row
        // per capability.
        java.util.Map<UUID, DigitalTwin> latestPerDevice = new java.util.HashMap<>();
        for (DigitalTwin twin : cache.values()) {
            latestPerDevice.merge(twin.deviceId(), twin,
                    (a, b) -> a.latestTimestamp().isAfter(b.latestTimestamp()) ? a : b);
        }
        return new ArrayList<>(latestPerDevice.values());
    }
}
