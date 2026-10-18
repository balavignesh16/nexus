package com.nexus.adapter.core;

import com.nexus.adapter.http.CredentialStore;
import com.nexus.adapter.http.HttpAdapterSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads device credentials (joined with the device) and records valid contacts. It never creates, changes or deletes a credential: that is the
 * administration CLI's job under its own database role (D7), and the backend's role needs only SELECT on the credentials and UPDATE of the
 * last-contact column. All time decisions use the database clock.
 */
@Component
@ConditionalOnProperty(name = "nexus.adapter.http.enabled", havingValue = "true")
public class JdbcCredentialStore implements CredentialStore {
    private final JdbcTemplate jdbc;
    private final Duration writeInterval;
    private final ConcurrentHashMap<UUID, Long> lastWriteNanos = new ConcurrentHashMap<>();

    public JdbcCredentialStore(JdbcTemplate jdbc, HttpAdapterSettings settings) {
        this.jdbc = jdbc;
        this.writeInterval = settings.contactWriteInterval();
    }

    @Override
    public Optional<Stored> find(String lookupId) {
        List<Stored> found = jdbc.query(
                "SELECT c.credential_id, c.device_id, c.lookup_id, c.secret_hash, c.hash_algorithm, c.status, c.retire_at, now() AS db_now, "
                        + "d.status AS device_status, d.transport FROM device_ingestion_credentials c JOIN devices d ON d.id = c.device_id WHERE c.lookup_id = ?",
                (rs, n) -> new Stored(rs.getObject("credential_id", UUID.class), rs.getObject("device_id", UUID.class), rs.getString("lookup_id"),
                        rs.getBytes("secret_hash"), rs.getString("hash_algorithm"), rs.getString("status"),
                        rs.getTimestamp("retire_at") == null ? null : rs.getTimestamp("retire_at").toInstant(),
                        rs.getTimestamp("db_now").toInstant(), rs.getString("device_status"), rs.getString("transport")),
                lookupId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public void recordContact(UUID credentialId, UUID deviceId) {
        long now = System.nanoTime();
        Long last = lastWriteNanos.get(deviceId);
        if (last != null && now - last < writeInterval.toNanos()) return;
        lastWriteNanos.put(deviceId, now);
        jdbc.update("UPDATE device_ingestion_credentials SET last_valid_contact_at = now() WHERE credential_id = ? "
                        + "AND (last_valid_contact_at IS NULL OR last_valid_contact_at < now() - make_interval(secs => ?))",
                credentialId, (double) writeInterval.toMillis() / 1000.0);
    }

    /** Test support: the stored instant of a credential's last valid contact. */
    Optional<Timestamp> lastContact(UUID credentialId) {
        List<Timestamp> found = jdbc.queryForList("SELECT last_valid_contact_at FROM device_ingestion_credentials WHERE credential_id = ?", Timestamp.class, credentialId);
        return found.isEmpty() || found.get(0) == null ? Optional.empty() : Optional.of(found.get(0));
    }
}
