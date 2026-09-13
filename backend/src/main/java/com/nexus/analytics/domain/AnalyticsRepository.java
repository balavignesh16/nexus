package com.nexus.analytics.domain;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All database-side aggregation for Analytics lives here (Frontend 2.0
 * Phase 2) - a single, small, reviewable place, deliberately isolated
 * from every other repository so this read-only reporting concern never
 * risks touching any write path. Every query is bounded by an explicit
 * [from, to) time range; nothing here ever scans an entire table
 * unbounded. Native SQL is used throughout because TimescaleDB's
 * time_bucket() has no JPQL equivalent, and this keeps every aggregation
 * query in one consistent style rather than mixing JPQL and native SQL.
 */
@Repository
public class AnalyticsRepository {

    private final EntityManager entityManager;

    public AnalyticsRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    // ---------- Summary: point-in-time / range-total counts ----------

    public long totalDeviceCount() {
        Number result = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM devices").getSingleResult();
        return result.longValue();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Long> deviceCountByType() {
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT device_type, COUNT(*) FROM devices GROUP BY device_type").getResultList();
        return toOrderedCountMap(rows);
    }

    /**
     * Connectivity distribution: a device with no device_connectivity row
     * is UNKNOWN - never treated as OFFLINE. The CASE/COALESCE happens in
     * SQL so "no row" and "OFFLINE" are never conflated at any layer.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Long> connectivityDistribution() {
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT COALESCE(dc.status, 'UNKNOWN') AS bucket, COUNT(*) "
                        + "FROM devices d LEFT JOIN device_connectivity dc ON dc.device_id = d.id "
                        + "GROUP BY COALESCE(dc.status, 'UNKNOWN')").getResultList();
        return toOrderedCountMap(rows);
    }

    public long eventCountInRange(Instant from, Instant to) {
        Query q = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM domain_events WHERE timestamp >= ?1 AND timestamp < ?2");
        q.setParameter(1, from);
        q.setParameter(2, to);
        return ((Number) q.getSingleResult()).longValue();
    }

    public long ruleMatchCountInRange(Instant from, Instant to) {
        Query q = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM rule_matches WHERE matched_at >= ?1 AND matched_at < ?2");
        q.setParameter(1, from);
        q.setParameter(2, to);
        return ((Number) q.getSingleResult()).longValue();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Long> actionStatusDistributionInRange(Instant from, Instant to) {
        Query q = entityManager.createNativeQuery(
                "SELECT status, COUNT(*) FROM action_history "
                        + "WHERE timestamp >= ?1 AND timestamp < ?2 GROUP BY status");
        q.setParameter(1, from);
        q.setParameter(2, to);
        List<Object[]> rows = q.getResultList();
        return toOrderedCountMap(rows);
    }

    /**
     * Only SEND_COMMAND rows participate. ack_status = NULL is reported as
     * NO_ACK_YET, never as a failure - it means either the command is
     * still awaiting acknowledgement or none will ever arrive (offline
     * device), not that acknowledgement failed.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Long> commandAckDistributionInRange(Instant from, Instant to) {
        Query q = entityManager.createNativeQuery(
                "SELECT COALESCE(ack_status, 'NO_ACK_YET') AS bucket, COUNT(*) FROM action_history "
                        + "WHERE action_type = 'SEND_COMMAND' AND timestamp >= ?1 AND timestamp < ?2 "
                        + "GROUP BY COALESCE(ack_status, 'NO_ACK_YET')");
        q.setParameter(1, from);
        q.setParameter(2, to);
        List<Object[]> rows = q.getResultList();
        return toOrderedCountMap(rows);
    }

    // ---------- Time series: bucketed counts ----------

    /**
     * telemetry_history is a TimescaleDB hypertable - time_bucket() is
     * used, not date_trunc(). bucketIntervalLiteral is always a trusted
     * AnalyticsBucket#intervalLiteral() value, never raw client input.
     */
    public List<TimeSeriesRow> telemetryTimeSeries(Instant from, Instant to, String bucketIntervalLiteral) {
        Query q = entityManager.createNativeQuery(
                "SELECT time_bucket(CAST(?1 AS interval), timestamp) AS bucket, COUNT(*) "
                        + "FROM telemetry_history WHERE timestamp >= ?2 AND timestamp < ?3 "
                        + "GROUP BY bucket ORDER BY bucket");
        q.setParameter(1, bucketIntervalLiteral);
        q.setParameter(2, from);
        q.setParameter(3, to);
        return toTimeSeriesRows(q.getResultList());
    }

    public List<TimeSeriesRow> eventsTimeSeries(Instant from, Instant to, String bucketPostgresUnit) {
        return dateTruncTimeSeries("domain_events", "timestamp", from, to, bucketPostgresUnit);
    }

    public List<TimeSeriesRow> ruleMatchesTimeSeries(Instant from, Instant to, String bucketPostgresUnit) {
        return dateTruncTimeSeries("rule_matches", "matched_at", from, to, bucketPostgresUnit);
    }

    public List<TimeSeriesRow> actionsTimeSeries(Instant from, Instant to, String bucketPostgresUnit) {
        return dateTruncTimeSeries("action_history", "timestamp", from, to, bucketPostgresUnit);
    }

    /**
     * pipeline_traces is a TimescaleDB hypertable keyed on
     * start_timestamp - time_bucket() is used, mirroring telemetry.
     */
    public List<TimeSeriesRow> pipelineTimeSeries(Instant from, Instant to, String bucketIntervalLiteral) {
        Query q = entityManager.createNativeQuery(
                "SELECT time_bucket(CAST(?1 AS interval), start_timestamp) AS bucket, COUNT(*) "
                        + "FROM pipeline_traces WHERE start_timestamp >= ?2 AND start_timestamp < ?3 "
                        + "GROUP BY bucket ORDER BY bucket");
        q.setParameter(1, bucketIntervalLiteral);
        q.setParameter(2, from);
        q.setParameter(3, to);
        return toTimeSeriesRows(q.getResultList());
    }

    // table/column names below are never client-controlled - each call
    // site passes a fixed literal, never a parameter derived from request
    // input, so this is not a SQL-injection risk despite the string
    // concatenation.
    private List<TimeSeriesRow> dateTruncTimeSeries(String table, String timestampColumn,
                                                      Instant from, Instant to, String bucketPostgresUnit) {
        Query q = entityManager.createNativeQuery(
                "SELECT date_trunc(?1, " + timestampColumn + ") AS bucket, COUNT(*) "
                        + "FROM " + table + " WHERE " + timestampColumn + " >= ?2 AND " + timestampColumn + " < ?3 "
                        + "GROUP BY bucket ORDER BY bucket");
        q.setParameter(1, bucketPostgresUnit);
        q.setParameter(2, from);
        q.setParameter(3, to);
        return toTimeSeriesRows(q.getResultList());
    }

    @SuppressWarnings("unchecked")
    private List<TimeSeriesRow> toTimeSeriesRows(List<Object[]> rows) {
        return rows.stream()
                .map(r -> new TimeSeriesRow(toInstant(r[0]), ((Number) r[1]).longValue()))
                .toList();
    }

    private Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        throw new IllegalStateException("Unexpected timestamp result type: " + value.getClass());
    }

    private Map<String, Long> toOrderedCountMap(List<Object[]> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String key = String.valueOf(row[0]);
            long count = row[1] instanceof BigDecimal bd ? bd.longValue() : ((Number) row[1]).longValue();
            result.put(key, count);
        }
        return result;
    }

    public record TimeSeriesRow(Instant bucket, long count) {}
}
