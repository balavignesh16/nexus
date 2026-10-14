package com.nexus.rule.persistence;

import com.nexus.rule.domain.CooldownScope;
import com.nexus.rule.domain.Rule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable cooldown authority (C14). The identity is the logical rule plus a scope key: '' (global, the only behavior of imported
 * rules) or the resolved device id for rules that explicitly opted into PER_SCOPE. A PER_SCOPE rule whose scope cannot be resolved
 * falls back to the global key, which can only suppress more, never less.
 * <p>
 * {@link #lockAndRead} must run inside the transaction that persists the RuleMatch: it takes the row FOR UPDATE (inserting an empty
 * placeholder first, so even the very first match of a rule is serialized by the primary key), which is what makes the check and the
 * advance atomic across JVM instances.
 */
@Repository
public class RuleCooldownStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public RuleCooldownStore(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public static String scopeKey(Rule rule, UUID scopeDevice) {
        return rule.cooldownScopeKey(scopeDevice);
    }

    /** Non-locking read for the cheap pre-check; never an authority on its own. */
    public Optional<Instant> last(UUID ruleId, String scopeKey) {
        var rows = jdbc.queryForList("SELECT last_matched_at FROM rule_cooldown_state WHERE rule_id=? AND scope_key=?", ruleId, scopeKey);
        if (rows.isEmpty() || rows.getFirst().get("last_matched_at") == null) return Optional.empty();
        return Optional.of(((Timestamp) rows.getFirst().get("last_matched_at")).toInstant());
    }

    /** Locks the (rule, scope) row for the rest of the transaction and returns the authoritative last match time. */
    public Optional<Instant> lockAndRead(UUID ruleId, String scopeKey) {
        jdbc.update("INSERT INTO rule_cooldown_state(rule_id,scope_key,updated_at) VALUES(?,?,?) ON CONFLICT (rule_id,scope_key) DO NOTHING",
            ruleId, scopeKey, Timestamp.from(clock.instant()));
        var rows = jdbc.queryForList("SELECT last_matched_at FROM rule_cooldown_state WHERE rule_id=? AND scope_key=? FOR UPDATE", ruleId, scopeKey);
        if (rows.isEmpty() || rows.getFirst().get("last_matched_at") == null) return Optional.empty();
        return Optional.of(((Timestamp) rows.getFirst().get("last_matched_at")).toInstant());
    }

    /** Called after the match row was flushed, in the same transaction: the cooldown never advances without its attributed match. */
    public void advance(UUID ruleId, String scopeKey, UUID matchId, Instant at) {
        jdbc.update("UPDATE rule_cooldown_state SET last_matched_at=?,last_match_id=?,updated_at=? WHERE rule_id=? AND scope_key=? AND (last_matched_at IS NULL OR last_matched_at<=?)",
            Timestamp.from(at), matchId, Timestamp.from(clock.instant()), ruleId, scopeKey, Timestamp.from(at));
    }
}
