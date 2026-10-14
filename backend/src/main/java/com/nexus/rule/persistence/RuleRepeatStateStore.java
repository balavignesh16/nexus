package com.nexus.rule.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/**
 * EDGE_TRUE latch (C14), scoped to the logical rule and resolved device. The latch carries the execution epoch it was set under,
 * so pause, resume or a new version starts a fresh generation without rewriting history. UNKNOWN or invalid evidence never
 * clears it: only definite false evidence rearms the edge.
 */
@Repository
public class RuleRepeatStateStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public RuleRepeatStateStore(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public boolean latched(UUID rule, UUID scope, long generation) {
        var rows = jdbc.queryForList("SELECT latched,generation FROM rule_repeat_state WHERE rule_id=? AND scope_device_id=?", rule, scope);
        return !rows.isEmpty() && ((Number) rows.getFirst().get("generation")).longValue() == generation && Boolean.TRUE.equals(rows.getFirst().get("latched"));
    }

    public void set(UUID rule, UUID scope, long generation, boolean latched) {
        jdbc.update("""
            INSERT INTO rule_repeat_state(rule_id,scope_device_id,generation,latched,updated_at) VALUES(?,?,?,?,?)
            ON CONFLICT (rule_id,scope_device_id) DO UPDATE SET generation=EXCLUDED.generation,latched=EXCLUDED.latched,updated_at=EXCLUDED.updated_at""",
            rule, scope, generation, latched, Timestamp.from(clock.instant()));
    }
}
