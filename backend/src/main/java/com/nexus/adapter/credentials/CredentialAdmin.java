package com.nexus.adapter.credentials;

import com.nexus.adapter.http.KeyFormat;
import com.nexus.adapter.http.KeyHasher;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The operations of the local credential-administration tool (decision D7, adapter-contract.md section 9.8.1): create, rotate, revoke, list and
 * audit. It is plain JDBC with no Spring and no network surface. Every operation is ONE transaction, so a failure at any step leaves no
 * partial credential and returns no key; every operation appends an audit event whose actor is {@code cli:<operating-system account>} and
 * which carries no secret material. The plaintext key exists only in the {@link Issued} value that create and rotate return; the database
 * stores the lookup id and {@code HMAC-SHA-256(pepper, secret)} only.
 */
public final class CredentialAdmin {
    public static final Duration DEFAULT_RETIRE_AFTER = Duration.ofHours(24);
    public static final Duration MAX_RETIRE_AFTER = Duration.ofDays(7);
    public static final Duration MIN_RETIRE_AFTER = Duration.ofMinutes(1);

    /** The one-time result of create and rotate. The key is deliberately not part of toString(). */
    public record Issued(UUID credentialId, String lookupId, String key) {
        @Override
        public String toString() {
            return "Issued[lookupId=" + lookupId + "]";
        }
    }

    public record Row(String lookupId, String status, Timestamp createdAt, Timestamp retireAt, Timestamp revokedAt, Timestamp lastValidContactAt) {}

    public record Event(Timestamp occurredAt, String eventType, String lookupId, String actor) {}

    /** A refusal with a fixed, secret-free message. */
    public static final class AdminException extends RuntimeException {
        public AdminException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    public interface ConnectionSource {
        Connection open() throws SQLException;
    }

    private final ConnectionSource connections;
    private final KeyHasher hasher;
    private final SecureRandom random;
    private final String actor;

    public CredentialAdmin(ConnectionSource connections, KeyHasher hasher, SecureRandom random, String operatingSystemAccount) {
        this.connections = connections;
        this.hasher = hasher;
        this.random = random;
        this.actor = "cli:" + operatingSystemAccount.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public Issued create(UUID deviceId) {
        return inTransaction(c -> {
            requireDevice(c, deviceId);
            if (exists(c, "SELECT 1 FROM device_ingestion_credentials WHERE device_id = ? AND status = 'ACTIVE'", deviceId)) {
                throw new AdminException("the device already has an ACTIVE credential; use rotate");
            }
            Issued issued = insertActive(c, deviceId);
            event(c, issued.credentialId(), "CREATED");
            return issued;
        });
    }

    public Issued rotate(UUID deviceId, Duration retireAfter) {
        if (retireAfter.compareTo(MIN_RETIRE_AFTER) < 0 || retireAfter.compareTo(MAX_RETIRE_AFTER) > 0) {
            throw new AdminException("--retire-after must be between PT1M and P7D");
        }
        return inTransaction(c -> {
            requireDevice(c, deviceId);
            if (exists(c, "SELECT 1 FROM device_ingestion_credentials WHERE device_id = ? AND status = 'RETIRING'", deviceId)) {
                throw new AdminException("the device already has a RETIRING credential; wait until it is gone or revoke it before rotating again");
            }
            UUID old = single(c, "SELECT credential_id FROM device_ingestion_credentials WHERE device_id = ? AND status = 'ACTIVE'", deviceId);
            if (old == null) throw new AdminException("the device has no ACTIVE credential; use create");
            try (PreparedStatement update = c.prepareStatement("UPDATE device_ingestion_credentials SET status = 'RETIRING', retire_at = now() + make_interval(secs => ?) WHERE credential_id = ?")) {
                update.setDouble(1, retireAfter.toMillis() / 1000.0);
                update.setObject(2, old);
                update.executeUpdate();
            }
            event(c, old, "RETIRING");
            Issued issued = insertActive(c, deviceId);
            event(c, issued.credentialId(), "ROTATED_IN");
            return issued;
        });
    }

    public void revoke(String lookupId) {
        inTransaction(c -> {
            UUID id = single(c, "SELECT credential_id FROM device_ingestion_credentials WHERE lookup_id = ? AND status <> 'REVOKED'", lookupId);
            if (id == null) throw new AdminException("no such credential, or it is already revoked");
            try (PreparedStatement update = c.prepareStatement("UPDATE device_ingestion_credentials SET status = 'REVOKED', revoked_at = now() WHERE credential_id = ?")) {
                update.setObject(1, id);
                update.executeUpdate();
            }
            event(c, id, "REVOKED");
            return null;
        });
    }

    /** Metadata only: never a secret, a key or a hash. */
    public List<Row> list(UUID deviceId) {
        return inTransaction(c -> {
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT lookup_id, status, created_at, retire_at, revoked_at, last_valid_contact_at FROM device_ingestion_credentials WHERE device_id = ? ORDER BY created_at")) {
                ps.setObject(1, deviceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new Row(rs.getString(1), rs.getString(2), rs.getTimestamp(3), rs.getTimestamp(4), rs.getTimestamp(5), rs.getTimestamp(6)));
                }
            }
            return rows;
        });
    }

    public List<Event> audit(UUID deviceId) {
        return inTransaction(c -> {
            List<Event> events = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT e.occurred_at, e.event_type, k.lookup_id, e.actor FROM device_credential_events e "
                    + "JOIN device_ingestion_credentials k ON k.credential_id = e.credential_id WHERE k.device_id = ? ORDER BY e.occurred_at, e.event_id")) {
                ps.setObject(1, deviceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) events.add(new Event(rs.getTimestamp(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
            return events;
        });
    }

    // ------------------------------------------------------------------ internals

    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T inTransaction(Work<T> work) {
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // the connection is closed below; the transaction cannot commit
                }
                if (e instanceof AdminException admin) throw admin;
                throw new AdminException("the operation failed and nothing was changed");
            }
        } catch (SQLException e) {
            throw new AdminException("the database cannot be reached with the configured login");
        }
    }

    private Issued insertActive(Connection c, UUID deviceId) throws SQLException {
        UUID credentialId = UUID.randomUUID();
        String lookupId = KeyFormat.newLookupId(random);
        String secret = KeyFormat.newSecret(random);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO device_ingestion_credentials(credential_id, device_id, lookup_id, secret_hash, hash_algorithm, status, created_at) "
                + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', now())")) {
            ps.setObject(1, credentialId);
            ps.setObject(2, deviceId);
            ps.setString(3, lookupId);
            ps.setBytes(4, hasher.hash(secret));
            ps.setString(5, KeyHasher.ALGORITHM);
            ps.executeUpdate();
        }
        return new Issued(credentialId, lookupId, KeyFormat.format(lookupId, secret));
    }

    private void event(Connection c, UUID credentialId, String type) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO device_credential_events(event_id, credential_id, event_type, occurred_at, actor) VALUES (?, ?, ?, clock_timestamp(), ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, credentialId);
            ps.setString(3, type);
            ps.setString(4, actor);
            ps.executeUpdate();
        }
    }

    private static void requireDevice(Connection c, UUID deviceId) throws SQLException {
        if (!exists(c, "SELECT 1 FROM devices WHERE id = ?", deviceId)) throw new AdminException("no such device");
    }

    private static boolean exists(Connection c, String sql, Object parameter) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, parameter);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static UUID single(Connection c, String sql, Object parameter) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, parameter);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        }
    }
}
