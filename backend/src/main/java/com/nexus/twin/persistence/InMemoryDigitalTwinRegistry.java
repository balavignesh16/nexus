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
    private final java.util.Set<DigitalTwinId> readThrough = ConcurrentHashMap.newKeySet();
    private final DigitalTwinRepository repository;

    public InMemoryDigitalTwinRegistry(DigitalTwinRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        log.info("Hydrating Digital Twin cache from database...");
        List<DigitalTwin> twins = repository.findAll();
        for (DigitalTwin twin : twins) {
            cache.put(new DigitalTwinId(twin.deviceId(), twin.stateKey()), twin);
        }
        log.info("Loaded {} twins into cache", twins.size());
    }

    @Override
    public void put(DigitalTwin twin) {
        // Authoritative Persistence First
        repository.save(twin);
        // Then update cache
        project(twin);
    }

    @Override
    public void project(DigitalTwin twin) {
        DigitalTwinId id=new DigitalTwinId(twin.deviceId(), twin.stateKey());
        cache.put(id, twin); readThrough.remove(id);
    }

    @Override
    public void evict(UUID deviceId, String stateKey) {
        DigitalTwinId id=new DigitalTwinId(deviceId,stateKey); cache.remove(id); readThrough.add(id);
    }

    @Override
    public Optional<DigitalTwin> getByStateKey(UUID deviceId, String stateKey) {
        DigitalTwinId id = new DigitalTwinId(deviceId, stateKey);
        DigitalTwin cached = cache.get(id);
        if (cached != null) return Optional.of(cached);
        Optional<DigitalTwin> durable = repository.findById(id);
        durable.ifPresent(t -> {cache.put(id,t);readThrough.remove(id);});
        return durable;
    }

    @Override
    public Optional<DigitalTwin> get(UUID deviceId) {
        return getAllForDevice(deviceId).stream()
                .max(Comparator.comparing(DigitalTwin::latestTimestamp));
    }

    @Override
    public Optional<DigitalTwin> get(UUID deviceId, String sensorType) {
        return getAllForDevice(deviceId).stream().filter(t -> sensorType.equals(t.latestSensorType())
                || sensorType.equals(t.lastWireIdentifier()) || sensorType.equals(t.capabilityKey())).findFirst();
    }

    @Override
    public List<DigitalTwin> getAllForDevice(UUID deviceId) {
        for(DigitalTwinId id:List.copyOf(readThrough))if(id.getDeviceId().equals(deviceId))getByStateKey(deviceId,id.getStateKey());
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
            DigitalTwinId id = new DigitalTwinId(twin.deviceId(), twin.stateKey());
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
