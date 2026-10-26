package com.nexus.intelligence.energy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read-only access to which devices have stored power or energy readings in a window. One fixed, bounded statement over the existing
 * (device_id, capability_key, timestamp) index; the capability keys are bound parameters from configuration, never text from a question. It runs inside the read-only
 * transaction opened by {@link EnergyService} and writes nothing.
 */
@Repository
public class EnergyReader {
    public record Candidate(UUID deviceId, String capabilityKey, String deviceName, String deviceMode) {}

    private final JdbcTemplate jdbc;

    public EnergyReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void boundStatements(int millis) {
        jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class, Integer.toString(millis));
    }

    public boolean deviceExists(UUID device) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM devices WHERE id = ?)", Boolean.class, device));
    }

    /** Devices (at most {@code limit}) with at least one reading of one of {@code keys} in [from, to), optionally only {@code device}. */
    public List<Candidate> candidates(List<String> keys, Instant from, Instant to, UUID device, int limit) {
        String marks = String.join(",", keys.stream().map(k -> "?").toList());
        List<Object> args = new ArrayList<>(keys);
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        args.add(device);
        args.add(device);
        args.add(limit);
        return jdbc.query("SELECT t.device_id, t.capability_key, d.name, d.mode FROM (SELECT DISTINCT device_id, capability_key FROM telemetry_history WHERE capability_key IN (" + marks
                + ") AND timestamp >= ? AND timestamp < ? AND (?::uuid IS NULL OR device_id = ?::uuid)) t JOIN devices d ON d.id = t.device_id ORDER BY t.device_id, t.capability_key LIMIT ?",
                (rs, n) -> new Candidate(rs.getObject("device_id", UUID.class), rs.getString("capability_key"), rs.getString("name"), rs.getString("mode")), args.toArray());
    }
}
