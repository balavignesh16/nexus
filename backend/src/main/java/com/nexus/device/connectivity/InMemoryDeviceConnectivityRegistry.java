package com.nexus.device.connectivity;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Write-through cached, database-backed connectivity registry (Tier 5B),
 * mirroring the same proven shape as
 * com.nexus.twin.persistence.InMemoryDigitalTwinRegistry: authoritative
 * persistence first, then cache update, with the full cache hydrated from
 * the database at startup - this is what makes a backend restart recover
 * the last known connectivity state immediately, before any MQTT
 * reconnect/resubscribe has had a chance to run.
 */
@Repository
public class InMemoryDeviceConnectivityRegistry implements DeviceConnectivityRegistry {

    private static final Logger log = LoggerFactory.getLogger(InMemoryDeviceConnectivityRegistry.class);

    private final ConcurrentMap<UUID, DeviceConnectivity> cache = new ConcurrentHashMap<>();
    private final DeviceConnectivityRepository repository;

    public InMemoryDeviceConnectivityRegistry(DeviceConnectivityRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        log.info("Hydrating device connectivity cache from database...");
        List<DeviceConnectivity> rows = repository.findAll();
        for (DeviceConnectivity row : rows) {
            cache.put(row.deviceId(), row);
        }
        log.info("Loaded {} device connectivity row(s) into cache", rows.size());
    }

    @Override
    public boolean applyObservedStatus(UUID deviceId, DeviceConnectivityStatus status) {
        DeviceConnectivity current = cache.get(deviceId);
        if (current != null && current.status() == status) {
            // Duplicate observation of the already-current status: harmless,
            // must not appear as a state transition (last_changed_at is left
            // untouched, no persistence write, no cache update).
            return false;
        }

        DeviceConnectivity updated = new DeviceConnectivity(deviceId, status, Instant.now());
        repository.save(updated);
        cache.put(deviceId, updated);
        return true;
    }

    @Override
    public Optional<DeviceConnectivity> get(UUID deviceId) {
        return Optional.ofNullable(cache.get(deviceId));
    }
}
