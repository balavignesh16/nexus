package com.nexus.rule.temporal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** JDBC access for the V28 tables. Methods that lock rows must run inside the caller's write transaction. */
@Repository
public class TemporalStateStore {
    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    public static final int EVIDENCE_PER_STATE = 200;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public TemporalStateStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    private static Timestamp ts(Instant at) { return at == null ? null : Timestamp.from(at); }
    private static Instant instant(Timestamp at) { return at == null ? null : at.toInstant(); }

    private TemporalRuntimeEvidence map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        try {
            return new TemporalRuntimeEvidence(rs.getObject("rule_id", UUID.class), rs.getObject("rule_version_id", UUID.class), rs.getLong("rule_revision"),
                rs.getString("condition_id"), rs.getObject("scope_device_id", UUID.class), rs.getLong("generation"), rs.getString("operator"), rs.getString("phase"),
                instant(rs.getTimestamp("armed_at")), instant(rs.getTimestamp("due_at")), instant(rs.getTimestamp("coverage_end")),
                mapper.readValue(rs.getString("members"), LIST), mapper.readValue(rs.getString("watermark"), MAP), rs.getLong("sequence"),
                rs.getString("reset_reason"), instant(rs.getTimestamp("updated_at")));
        } catch (java.io.IOException e) { throw new IllegalStateException("Unreadable temporal state", e); }
    }

    private static final String COLUMNS = "rule_id,rule_version_id,rule_revision,condition_id,scope_device_id,generation,operator,phase,armed_at,due_at,coverage_end,members::text AS members,watermark::text AS watermark,sequence,reset_reason,updated_at";

    /** Creates the IDLE row when absent, then locks it. */
    public TemporalRuntimeEvidence lockOrCreate(UUID rule, UUID version, long revision, String condition, UUID scope, long generation, String operator, Instant now) {
        jdbc.update("""
            INSERT INTO rule_temporal_state(rule_id,rule_version_id,rule_revision,condition_id,scope_device_id,generation,operator,phase,updated_at)
            VALUES(?,?,?,?,?,?,?,'IDLE',?) ON CONFLICT DO NOTHING""", rule, version, revision, condition, scope, generation, operator, ts(now));
        return jdbc.query("SELECT " + COLUMNS + " FROM rule_temporal_state WHERE rule_version_id=? AND condition_id=? AND scope_device_id=? AND generation=? FOR UPDATE",
            this::map, version, condition, scope, generation).getFirst();
    }

    public Optional<TemporalRuntimeEvidence> find(UUID version, String condition, UUID scope, long generation) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rule_temporal_state WHERE rule_version_id=? AND condition_id=? AND scope_device_id=? AND generation=?",
            this::map, version, condition, scope, generation).stream().findFirst();
    }

    /** Frozen-snapshot read: the rows of one version and generation. */
    public List<TemporalRuntimeEvidence> forVersion(UUID version, long generation) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rule_temporal_state WHERE rule_version_id=? AND generation=?", this::map, version, generation);
    }

    public void save(TemporalRuntimeEvidence s, Instant now) {
        jdbc.update("""
            UPDATE rule_temporal_state SET phase=?,armed_at=?,due_at=?,coverage_end=?,members=?::jsonb,watermark=?::jsonb,sequence=?,reset_reason=?,updated_at=?
            WHERE rule_version_id=? AND condition_id=? AND scope_device_id=? AND generation=?""",
            s.phase(), ts(s.armedAt()), ts(s.dueAt()), ts(s.coverageEnd()), json(s.members()), json(s.watermark()), s.sequence(), s.resetReason(), ts(now),
            s.ruleVersionId(), s.conditionId(), s.scopeDeviceId(), s.generation());
    }

    /** Anything not owned by the active (version, generation) cannot carry progress forward. */
    public void invalidateStale(UUID rule, UUID activeVersion, long generation, Instant now) {
        jdbc.update("UPDATE rule_temporal_state SET phase='IDLE',members='[]'::jsonb,armed_at=NULL,due_at=NULL,coverage_end=NULL,reset_reason='OWNERSHIP_CHANGED',updated_at=? WHERE rule_id=? AND (rule_version_id<>? OR generation<>?) AND phase<>'IDLE'",
            ts(now), rule, activeVersion, generation);
    }

    public void invalidateRule(UUID rule, String reason, Instant now) {
        jdbc.update("UPDATE rule_temporal_state SET phase='IDLE',members='[]'::jsonb,armed_at=NULL,due_at=NULL,coverage_end=NULL,reset_reason=?,updated_at=? WHERE rule_id=? AND phase<>'IDLE'",
            reason, ts(now), rule);
    }

    /** Appends evidence and bounds the per-state history. */
    public void evidence(TemporalRuntimeEvidence s, String event, Instant at, Map<String, Object> details) {
        jdbc.update("INSERT INTO rule_temporal_evidence(rule_id,rule_version_id,condition_id,scope_device_id,generation,event,occurred_at,details) VALUES(?,?,?,?,?,?,?,?::jsonb)",
            s.ruleId(), s.ruleVersionId(), s.conditionId(), s.scopeDeviceId(), s.generation(), event, ts(at), json(details));
        jdbc.update("""
            DELETE FROM rule_temporal_evidence WHERE rule_version_id=? AND condition_id=? AND scope_device_id=? AND generation=?
              AND seq NOT IN (SELECT seq FROM rule_temporal_evidence WHERE rule_version_id=? AND condition_id=? AND scope_device_id=? AND generation=? ORDER BY seq DESC LIMIT ?)""",
            s.ruleVersionId(), s.conditionId(), s.scopeDeviceId(), s.generation(), s.ruleVersionId(), s.conditionId(), s.scopeDeviceId(), s.generation(), EVIDENCE_PER_STATE);
    }

    public List<Map<String, Object>> evidenceFor(UUID rule, int limit) {
        return jdbc.query("SELECT seq,rule_version_id,condition_id,scope_device_id,generation,event,occurred_at,details::text AS details FROM rule_temporal_evidence WHERE rule_id=? ORDER BY seq DESC LIMIT ?",
            (rs, n) -> {
                try {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("seq", rs.getLong("seq")); row.put("ruleVersionId", rs.getObject("rule_version_id", UUID.class));
                    row.put("conditionId", rs.getString("condition_id")); row.put("scopeDeviceId", rs.getObject("scope_device_id", UUID.class));
                    row.put("generation", rs.getLong("generation")); row.put("event", rs.getString("event"));
                    row.put("occurredAt", rs.getTimestamp("occurred_at").toInstant()); row.put("details", mapper.readValue(rs.getString("details"), MAP));
                    return row;
                } catch (java.io.IOException e) { throw new IllegalStateException(e); }
            }, rule, Math.min(Math.max(limit, 1), 500));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
