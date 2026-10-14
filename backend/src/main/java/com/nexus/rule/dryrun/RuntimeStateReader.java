package com.nexus.rule.dryrun;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * READ-ONLY view of the production cooldown and EDGE_TRUE latch state, for CURRENT_STATE dry-runs. It has no write method on purpose: the
 * dry-run engine must never be able to advance a production gate. (Hysteresis latches and temporal rows are read by the evaluation snapshot.)
 */
@Repository
public class RuntimeStateReader {
    private final JdbcTemplate jdbc;
    public RuntimeStateReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Instant> cooldownLast(UUID ruleId, String scopeKey) {
        var rows = jdbc.queryForList("SELECT last_matched_at FROM rule_cooldown_state WHERE rule_id=? AND scope_key=?", ruleId, scopeKey);
        if (rows.isEmpty() || rows.getFirst().get("last_matched_at") == null) return Optional.empty();
        return Optional.of(((Timestamp) rows.getFirst().get("last_matched_at")).toInstant());
    }

    public boolean edgeLatched(UUID ruleId, UUID scope, long generation) {
        var rows = jdbc.queryForList("SELECT latched,generation FROM rule_repeat_state WHERE rule_id=? AND scope_device_id=?", ruleId, scope);
        return !rows.isEmpty() && ((Number) rows.getFirst().get("generation")).longValue() == generation && Boolean.TRUE.equals(rows.getFirst().get("latched"));
    }
}
