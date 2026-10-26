package com.nexus.intelligence.recommend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only reader of persisted Phase 3 and Phase 4 facts. Command failures come from the append-only command_state_history (window on its occurred_at
 * index, joined by primary key to the command's device); verification trouble from current_twin_verification_state (the CURRENT per-target status, not a
 * history); rule suppressions from the persisted arbitration candidates, reached through the rule's versions and matches so the existing
 * (rule_version_id, matched_at) index bounds the scan. Every query has a window and a row limit.
 *
 * <p>TIME AUTHORITY. Each fact source is evaluated on the clock that stamped it. command_state_history.occurred_at is the DATABASE clock by Phase 4 contract
 * (R37: clock_timestamp() at insertion), so its window is bounded by {@link #databaseNow()}, never by the backend clock: the two can differ by milliseconds
 * and a fact committed a moment ago could otherwise fall outside the window. Verification state and rule matches are stamped by the backend clock and use it.
 */
@Repository
public class FailureFactsReader {
    public record DeviceFailures(UUID deviceId, int deadLettered, int ackTimedOut, int deliveryUnknown, int verificationDiverged, int verificationTimedOut, List<String> refs) {
        public int total() {
            return deadLettered + ackTimedOut + deliveryUnknown + verificationDiverged + verificationTimedOut;
        }
    }

    public record Conflict(UUID suppressedRule, UUID winningRule, int count, Map<String, Integer> byDisposition, List<String> refs) {}

    private static final int GROUP_LIMIT = 500;
    private static final int REF_LIMIT = 20;

    private final JdbcTemplate jdbc;

    public FailureFactsReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The database clock now: the upper bound of any window over database-stamped facts. */
    public Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    /**
     * Failure counts per device; {@code device} null means every device (still bounded by the group limit). Devices with no failures are absent.
     * Command history is read in [dbFrom, dbTo) on the database clock, verification state in [from, to) on the backend clock.
     */
    public List<DeviceFailures> failures(UUID device, Instant from, Instant to, Instant dbFrom, Instant dbTo) {
        Map<UUID, int[]> counts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT a.device_id, h.fact_type, COUNT(*) AS n
                  FROM command_state_history h JOIN action_history a ON a.request_id = h.command_id
                 WHERE h.occurred_at >= ? AND h.occurred_at < ? AND h.fact_type IN ('DEAD_LETTERED','ACK_TIMED_OUT','DELIVERY_UNKNOWN')
                   AND a.device_id IS NOT NULL AND (?::uuid IS NULL OR a.device_id = ?::uuid)
                 GROUP BY a.device_id, h.fact_type ORDER BY a.device_id, h.fact_type LIMIT ?
                """, rs -> {
            int[] c = counts.computeIfAbsent(rs.getObject("device_id", UUID.class), k -> new int[5]);
            int n = rs.getInt("n");
            switch (rs.getString("fact_type")) {
                case "DEAD_LETTERED" -> c[0] += n;
                case "ACK_TIMED_OUT" -> c[1] += n;
                default -> c[2] += n;
            }
        }, Timestamp.from(dbFrom), Timestamp.from(dbTo), device, device, GROUP_LIMIT);
        jdbc.query("""
                SELECT device_id, verification_status, COUNT(*) AS n FROM current_twin_verification_state
                 WHERE verification_status IN ('DIVERGED','TIMED_OUT') AND verification_updated_at >= ? AND verification_updated_at < ?
                   AND (?::uuid IS NULL OR device_id = ?::uuid)
                 GROUP BY device_id, verification_status ORDER BY device_id, verification_status LIMIT ?
                """, rs -> {
            int[] c = counts.computeIfAbsent(rs.getObject("device_id", UUID.class), k -> new int[5]);
            if (rs.getString("verification_status").equals("DIVERGED")) c[3] += rs.getInt("n");
            else c[4] += rs.getInt("n");
        }, Timestamp.from(from), Timestamp.from(to), device, device, GROUP_LIMIT);
        List<DeviceFailures> out = new ArrayList<>();
        for (Map.Entry<UUID, int[]> e : counts.entrySet()) {
            int[] c = e.getValue();
            out.add(new DeviceFailures(e.getKey(), c[0], c[1], c[2], c[3], c[4], refs(e.getKey(), from, to, dbFrom, dbTo)));
        }
        return out;
    }

    private List<String> refs(UUID device, Instant from, Instant to, Instant dbFrom, Instant dbTo) {
        List<String> refs = new ArrayList<>(jdbc.query("""
                SELECT h.command_id, h.sequence FROM command_state_history h JOIN action_history a ON a.request_id = h.command_id
                 WHERE a.device_id = ? AND h.occurred_at >= ? AND h.occurred_at < ? AND h.fact_type IN ('DEAD_LETTERED','ACK_TIMED_OUT','DELIVERY_UNKNOWN')
                 ORDER BY h.occurred_at, h.command_id, h.sequence LIMIT ?
                """, (rs, n) -> "command:" + rs.getObject("command_id", UUID.class) + "/fact/" + rs.getInt("sequence"), device, Timestamp.from(dbFrom), Timestamp.from(dbTo), REF_LIMIT));
        refs.addAll(jdbc.query("""
                SELECT desired_command_id, target_key, subtarget_key FROM current_twin_verification_state
                 WHERE device_id = ? AND verification_status IN ('DIVERGED','TIMED_OUT') AND verification_updated_at >= ? AND verification_updated_at < ?
                 ORDER BY target_key, subtarget_key LIMIT ?
                """, (rs, n) -> "verification:" + device + "/" + rs.getString("target_key") + (rs.getString("subtarget_key").isEmpty() ? "" : "/" + rs.getString("subtarget_key")),
                device, Timestamp.from(from), Timestamp.from(to), REF_LIMIT));
        return refs.size() > REF_LIMIT ? refs.subList(0, REF_LIMIT) : refs;
    }

    /** Suppressions of this rule's candidates in [from, to), grouped by the winning rule. */
    public List<Conflict> conflicts(UUID rule, Instant from, Instant to) {
        Map<UUID, Conflict> out = new LinkedHashMap<>();
        jdbc.query("""
                WITH m AS (
                  SELECT m.match_id FROM rule_versions v JOIN rule_matches m ON m.rule_version_id = v.version_id
                   WHERE v.rule_id = ? AND m.matched_at >= ? AND m.matched_at < ? ORDER BY m.matched_at DESC LIMIT 5000)
                SELECT w.rule_id AS winner, c.disposition, c.candidate_id
                  FROM m JOIN rule_action_candidates c ON c.match_id = m.match_id AND c.rule_id = ?
                  JOIN rule_action_candidates w ON w.candidate_id = c.winner_candidate_id
                 WHERE c.disposition IN ('SUPPRESSED_PRIORITY','SUPPRESSED_CONFLICT') AND w.rule_id <> c.rule_id
                 ORDER BY w.rule_id, c.candidate_id LIMIT ?
                """, rs -> {
            UUID winner = rs.getObject("winner", UUID.class);
            Conflict prior = out.get(winner);
            Map<String, Integer> by = prior == null ? new LinkedHashMap<>() : new LinkedHashMap<>(prior.byDisposition());
            by.merge(rs.getString("disposition"), 1, Integer::sum);
            List<String> refs = prior == null ? new ArrayList<>() : new ArrayList<>(prior.refs());
            if (refs.size() < REF_LIMIT) refs.add("candidate:" + rs.getObject("candidate_id", UUID.class));
            out.put(winner, new Conflict(rule, winner, (prior == null ? 0 : prior.count()) + 1, by, refs));
        }, rule, Timestamp.from(from), Timestamp.from(to), rule, GROUP_LIMIT * 10);
        return new ArrayList<>(out.values());
    }

    /** Active rule ids in a stable order, at most {@code limit}: the bound on a fleet-wide conflict scan. */
    public List<UUID> activeRules(int limit) {
        return jdbc.queryForList("SELECT rule_id FROM rules WHERE lifecycle = 'ACTIVE' ORDER BY rule_id LIMIT ?", UUID.class, limit);
    }
}
