package com.nexus.rule.plan;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/**
 * Records, on the persisted SELECTED candidate, that guarded acceptance DENIED it. The denial itself happens inside the acceptance
 * transaction, which rolls back; this note is written afterwards in its own transaction and is evidence only, never an authority.
 */
@Component
public class RuleCandidateOutcomes {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public RuleCandidateOutcomes(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public void denied(UUID requestId, String reason) {
        try {
            jdbc.update("UPDATE rule_action_candidates SET outcome='DENIED',outcome_reason=left(?,500),outcome_at=? WHERE request_id=? AND outcome IS NULL",
                reason, Timestamp.from(clock.instant()), requestId);
        } catch (RuntimeException bookkeeping) {
            org.slf4j.LoggerFactory.getLogger(RuleCandidateOutcomes.class).warn("RULE_CANDIDATE_OUTCOME_NOT_RECORDED {}: {}", requestId, bookkeeping.getMessage());
        }
    }
}
