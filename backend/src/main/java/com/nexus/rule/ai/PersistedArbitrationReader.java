package com.nexus.rule.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** Read-only retrieval of the persisted arbitration of ONE match: candidates, dispositions, winners, outcomes and ActionHistory facts. Never re-decides anything. */
@Component
public class PersistedArbitrationReader {
    private final JdbcTemplate jdbc;
    public PersistedArbitrationReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<CandidateSummary> forMatch(UUID matchId) {
        return jdbc.query("""
            SELECT k.candidate_id,k.rule_id,k.action_ordinal,k.action_type,k.target_key,k.priority,k.disposition,k.reason,
                   w.action_type AS winner_type,w.target_key AS winner_target,k.outcome,k.outcome_reason,
                   h.status AS history_status,h.published_at,h.ack_status,
                   v.new_verification_status AS verification_status,v.readback_evidence,v.verification_reason
            FROM rule_action_candidates k
            LEFT JOIN rule_action_candidates w ON w.candidate_id=k.winner_candidate_id
            LEFT JOIN action_history h ON h.request_id=k.request_id
            LEFT JOIN LATERAL (SELECT t.new_verification_status,t.readback_evidence,t.verification_reason FROM twin_state_transitions t
                               WHERE t.verification_command_id=k.request_id AND t.new_verification_status IS NOT NULL
                               ORDER BY t.transitioned_at DESC,t.transition_id DESC LIMIT 1) v ON TRUE
            WHERE k.match_id=? ORDER BY k.action_ordinal""",
            (rs, n) -> {
                Timestamp published = rs.getTimestamp("published_at");
                return new CandidateSummary(rs.getObject("candidate_id", UUID.class), rs.getObject("rule_id", UUID.class), rs.getInt("action_ordinal"), rs.getString("action_type"),
                    rs.getString("target_key"), rs.getInt("priority"), rs.getString("disposition"), rs.getString("reason"), rs.getString("winner_type"), rs.getString("winner_target"),
                    rs.getString("outcome"), rs.getString("outcome_reason"), rs.getString("history_status"), published == null ? null : published.toInstant(), rs.getString("ack_status"),
                    rs.getString("verification_status"), rs.getString("readback_evidence"), rs.getString("verification_reason"));
            }, matchId);
    }
}
