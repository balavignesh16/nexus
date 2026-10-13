package com.nexus.rule.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.rule.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Reads immutable executable content; root JSON is a compatibility projection only. */
@Repository
public class RuleVersionStore {
    public record View(UUID ruleId, String lifecycle, long rowRevision, long executionEpoch,
            UUID activeVersionId, UUID workingVersionId, String workingState,
            String reconciliation, Boolean originalEnabled, boolean restorationEligible,
            Rule definition, List<Map<String,Object>> diagnostics) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public RuleVersionStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }

    public List<View> list(boolean activeOnly) {
        return read(activeOnly,null);
    }
    private List<View> read(boolean activeOnly,UUID ruleId) {
        return jdbc.query("""
            SELECT r.*, v.definition::text, v.revision AS definition_revision,
                w.state AS working_state,
                COALESCE((SELECT a.diagnostics FROM rule_validation_attempts a
                  WHERE a.rule_id=r.rule_id ORDER BY a.attempt_order DESC LIMIT 1),'[]'::jsonb)::text AS diagnostics
            FROM rules r
            JOIN rule_versions v ON v.version_id =
              CASE WHEN ? THEN r.active_version_id ELSE r.working_version_id END
            JOIN rule_version_workflow w ON w.version_id=r.working_version_id
            WHERE r.lifecycle <> 'ARCHIVED' AND (NOT ? OR
              (r.lifecycle='ACTIVE' AND r.reconciliation IN ('PASSED','NOT_REQUIRED')))
              AND (?::uuid IS NULL OR r.rule_id=?::uuid)
            ORDER BY (v.definition->>'priority')::integer, r.rule_id
            """, (rs,n) -> {
                try {
                    Rule raw=mapper.readValue(rs.getString("definition"),Rule.class);
                    UUID active=rs.getObject("active_version_id",UUID.class);
                    UUID working=rs.getObject("working_version_id",UUID.class);
                    long epoch=rs.getLong("execution_epoch");
                    Rule rule=new Rule(raw.ruleId(),raw.name(),"ACTIVE".equals(rs.getString("lifecycle")),
                        raw.priority(),raw.conditions(),raw.actions(),raw.metadata(),raw.cooldownSeconds(),
                        activeOnly ? new RuleExecutionIdentity(active,rs.getLong("definition_revision"),epoch) : null);
                    @SuppressWarnings("unchecked")
                    List<Map<String,Object>> diagnostics=mapper.readValue(rs.getString("diagnostics"),List.class);
                    return new View(raw.ruleId(),rs.getString("lifecycle"),rs.getLong("row_revision"),epoch,
                        active,working,rs.getString("working_state"),rs.getString("reconciliation"),
                        (Boolean)rs.getObject("original_enabled"),rs.getBoolean("restoration_eligible"),rule,diagnostics);
                } catch (Exception e) { throw new IllegalStateException("Unreadable persisted rule version",e); }
            },activeOnly,activeOnly,ruleId,ruleId);
    }
    public Optional<View> get(UUID id) { return read(false,id).stream().findFirst(); }
    public List<View> pendingBatch(int limit) {
        if(limit<1||limit>1000)throw new IllegalArgumentException("Reconciliation batch must be bounded");
        return jdbc.queryForList("SELECT rule_id FROM rules WHERE reconciliation='PENDING' AND lifecycle<>'ARCHIVED' ORDER BY rule_id LIMIT ?",UUID.class,limit)
            .stream().map(this::get).flatMap(Optional::stream).toList();
    }
    public List<Map<String,Object>> history(UUID id) {
        return jdbc.query("SELECT v.version_id, v.revision, v.definition::text, v.definition_format, v.created_at, v.provenance, w.state FROM rule_versions v JOIN rule_version_workflow w USING(version_id) WHERE v.rule_id=? ORDER BY v.revision",(rs,n)->{
            try { return Map.<String,Object>of("versionId",rs.getObject("version_id",UUID.class),"revision",rs.getLong("revision"),"definition",mapper.readValue(rs.getString("definition"),Map.class),"definitionFormat",rs.getInt("definition_format"),"createdAt",rs.getTimestamp("created_at").toInstant(),"provenance",rs.getString("provenance"),"state",rs.getString("state")); }
            catch(Exception failure){throw new IllegalStateException("Unreadable version history",failure);}
        },id);
    }
    public Rule version(UUID ruleId,UUID versionId) {
        String json=jdbc.queryForObject("SELECT definition::text FROM rule_versions WHERE rule_id=? AND version_id=?",String.class,ruleId,versionId);
        try { return mapper.readValue(json,Rule.class); }
        catch(Exception e) { throw new IllegalStateException("Unreadable persisted definition",e); }
    }
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(Exception e) { throw new IllegalArgumentException("Invalid rule JSON",e); }
    }
    public boolean sameDefinition(Rule a,Rule b) {
        return Objects.equals(a.name(),b.name()) && a.priority()==b.priority()
            && Objects.equals(a.conditions(),b.conditions()) && Objects.equals(a.actions(),b.actions())
            && Objects.equals(a.metadata(),b.metadata()) && Objects.equals(a.cooldownSeconds(),b.cooldownSeconds());
    }
}
