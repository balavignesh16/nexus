package com.nexus.intelligence.anomaly;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to the persisted facts the detectors need. Every query is bounded by a time window and a row limit, uses the existing
 * (device_id, capability_key, timestamp) index, and runs inside a read-only transaction opened by the caller (the database then refuses any write). Nothing
 * here writes, and nothing here is on the ingestion path.
 */
@Repository
public class TelemetrySeriesReader {
    public record Read(List<RawRow> rows, boolean truncated) {}

    public record Connectivity(String status, Instant lastChangedAt) {}

    public record Range(Double minimum, Double maximum) {}

    private final JdbcTemplate jdbc;

    public TelemetrySeriesReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Bounds the statements of the CURRENT transaction: a slow analysis is cut off instead of holding a connection. */
    public void boundStatements(int millis) {
        jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class, Integer.toString(millis));
    }

    public boolean deviceExists(UUID device) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM devices WHERE id = ?)", Boolean.class, device));
    }

    /** Capability keys that have at least one reading in [from, to), alphabetically, at most {@code limit}. */
    public List<String> capabilities(UUID device, Instant from, Instant to, int limit) {
        return jdbc.queryForList("SELECT DISTINCT capability_key FROM telemetry_history WHERE device_id = ? AND capability_key IS NOT NULL AND timestamp >= ? AND timestamp < ? ORDER BY capability_key LIMIT ?",
                String.class, device, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to), limit);
    }

    /** The newest {@code limit} readings in [from, to), returned oldest first; {@code truncated} says older readings in the window were not read. */
    public Read read(UUID device, String capability, Instant from, Instant to, int limit) {
        List<RawRow> rows = new ArrayList<>(jdbc.query(
                "SELECT id, timestamp, value_type, numeric_value, unit FROM telemetry_history WHERE device_id = ? AND capability_key = ? AND timestamp >= ? AND timestamp < ?"
                        + " ORDER BY timestamp DESC, id LIMIT ?",
                (rs, n) -> {
                    BigDecimal v = rs.getBigDecimal("numeric_value");
                    return new RawRow(rs.getObject("id", UUID.class).toString(), rs.getTimestamp("timestamp").toInstant(), rs.getString("value_type"), v == null ? null : v.doubleValue(), rs.getString("unit"));
                },
                device, capability, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to), limit + 1));
        boolean truncated = rows.size() > limit;
        if (truncated) rows = new ArrayList<>(rows.subList(0, limit));
        Collections.reverse(rows);
        return new Read(rows, truncated);
    }

    public Optional<Connectivity> connectivity(UUID device) {
        return jdbc.query("SELECT status, last_changed_at FROM device_connectivity WHERE device_id = ?",
                (rs, n) -> new Connectivity(rs.getString("status"), rs.getTimestamp("last_changed_at").toInstant()), device).stream().findFirst();
    }

    /** The capability definition's declared range, when it declares one (keeps a suggested threshold inside what the capability can report). */
    public Optional<Range> range(String capabilityKey) {
        return jdbc.query("SELECT minimum_value, maximum_value FROM capability_definitions WHERE capability_key = ?",
                (rs, n) -> new Range(rs.getBigDecimal("minimum_value") == null ? null : rs.getBigDecimal("minimum_value").doubleValue(),
                        rs.getBigDecimal("maximum_value") == null ? null : rs.getBigDecimal("maximum_value").doubleValue()), capabilityKey).stream().findFirst();
    }

    /** Devices in a stable order, at most {@code limit}: the bound on any fleet-wide analysis. */
    public List<UUID> devices(int limit) {
        return jdbc.queryForList("SELECT id FROM devices ORDER BY id LIMIT ?", UUID.class, limit);
    }
}
