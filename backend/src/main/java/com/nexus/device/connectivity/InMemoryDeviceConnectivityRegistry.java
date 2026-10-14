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

    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private org.springframework.transaction.PlatformTransactionManager transactions;
    private com.nexus.rule.trigger.RuleSourceFacts ruleFacts;
    private java.time.Clock clock = java.time.Clock.systemUTC();

    /** Optional wiring: when present, transitions are decided by the database row, not the cache. */
    @org.springframework.beans.factory.annotation.Autowired
    void setTransitionAuthority(org.springframework.jdbc.core.JdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager transactions,
            java.time.Clock clock, @org.springframework.context.annotation.Lazy com.nexus.rule.trigger.RuleSourceFacts ruleFacts) {
        this.jdbc = jdbc; this.transactions = transactions; this.clock = clock; this.ruleFacts = ruleFacts;
    }

    @Override
    public boolean applyObservedStatus(UUID deviceId, DeviceConnectivityStatus status) {
        if (jdbc != null && transactions != null) return applyPersistedTransition(deviceId, status);
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

    /**
     * The committed row decides whether this is a transition (so a stale cache or a second instance cannot invent
     * or hide one). Each real change increments a persisted generation, the CONNECTIVITY_CHANGED identity, and its
     * source fact commits in the same transaction. The cache is updated only after that commit.
     */
    private boolean applyPersistedTransition(UUID deviceId, DeviceConnectivityStatus status) {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        record Changed(Instant at) {}
        Changed changed = tx.execute(state -> {
            var rows = jdbc.queryForList("SELECT status,generation FROM device_connectivity WHERE device_id=? FOR UPDATE", deviceId);
            Instant at = clock.instant();
            String previous = rows.isEmpty() ? null : rows.getFirst().get("status").toString();
            if (previous != null && previous.equals(status.name())) return null;
            long generation;
            if (previous == null) {
                jdbc.update("INSERT INTO device_connectivity(device_id,status,last_changed_at,generation) VALUES(?,?,?,1)",
                    deviceId, status.name(), java.sql.Timestamp.from(at));
                generation = 1;
            } else {
                generation = ((Number) rows.getFirst().get("generation")).longValue() + 1;
                jdbc.update("UPDATE device_connectivity SET status=?,last_changed_at=?,generation=? WHERE device_id=?",
                    status.name(), java.sql.Timestamp.from(at), generation, deviceId);
            }
            if (ruleFacts != null) ruleFacts.connectivityChanged(deviceId, generation, previous, status.name(), at);
            return new Changed(at);
        });
        if (changed == null) {
            // Keep a lagging cache consistent with the authoritative row.
            if (cache.get(deviceId) == null || cache.get(deviceId).status() != status)
                repository.findById(deviceId).ifPresent(row -> cache.put(deviceId, row));
            return false;
        }
        cache.put(deviceId, new DeviceConnectivity(deviceId, status, changed.at()));
        return true;
    }

    @Override
    public Optional<DeviceConnectivity> get(UUID deviceId) {
        return Optional.ofNullable(cache.get(deviceId));
    }
}
