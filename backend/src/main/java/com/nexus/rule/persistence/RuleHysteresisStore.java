package com.nexus.rule.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Restart-safe numeric hysteresis latches (C14). A write carries the observation time it was derived from and never overwrites a newer
 * observation of the same generation, so a slow instance cannot undo a newer ENTER/EXIT. Only definite ENTER/EXIT transitions are
 * written: UNKNOWN, stale or non-GOOD evidence leaves the latch alone.
 */
@Repository
public class RuleHysteresisStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public RuleHysteresisStore(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    /** Latch of the current generation only; an older generation (before pause/resume/new version) reads as unlatched. */
    public boolean latched(UUID rule, String conditionId, UUID scope, long generation) {
        var rows = jdbc.queryForList("SELECT latched FROM rule_hysteresis_state WHERE rule_id=? AND condition_id=? AND scope_device_id=? AND generation=?",
            rule, conditionId, scope, generation);
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst().get("latched"));
    }

    public void apply(UUID rule, String conditionId, UUID scope, long generation, boolean latched, Instant observedAt) {
        jdbc.update("""
            INSERT INTO rule_hysteresis_state(rule_id,condition_id,scope_device_id,generation,latched,observed_at,updated_at) VALUES(?,?,?,?,?,?,?)
            ON CONFLICT (rule_id,condition_id,scope_device_id) DO UPDATE SET generation=EXCLUDED.generation,latched=EXCLUDED.latched,
                observed_at=EXCLUDED.observed_at,updated_at=EXCLUDED.updated_at
            WHERE rule_hysteresis_state.generation<>EXCLUDED.generation OR rule_hysteresis_state.observed_at<=EXCLUDED.observed_at""",
            rule, conditionId, scope, generation, latched, Timestamp.from(observedAt), Timestamp.from(clock.instant()));
    }
}
