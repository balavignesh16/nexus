package com.nexus.rule.api;

import com.nexus.rule.temporal.TemporalRuntimeEvidence;
import com.nexus.rule.temporal.TemporalStateStore;
import com.nexus.rule.trigger.RuleManualService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Trigger, timer, temporal-evidence and action-plan inspection plus the MANUAL source. Read endpoints expose persisted facts only. */
@RestController
@RequestMapping("/api/v1/rules/{id}")
public class RuleRuntimeController {
    public record ManualRequest(UUID expectedVersionId) {}
    public record ManualResponse(UUID requestId, UUID factId, UUID ruleVersionId, String note) {}
    private final RuleManualService manual;
    private final TemporalStateStore temporal;
    private final JdbcTemplate jdbc;
    public RuleRuntimeController(RuleManualService manual, TemporalStateStore temporal, JdbcTemplate jdbc) { this.manual = manual; this.temporal = temporal; this.jdbc = jdbc; }

    @PostMapping("/manual-executions")
    public ResponseEntity<ManualResponse> manual(@PathVariable UUID id, @RequestBody(required = false) ManualRequest request) {
        var issued = manual.request(id, request == null ? null : request.expectedVersionId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ManualResponse(issued.requestId(), issued.factId(), issued.ruleVersionId(),
            "Accepted as a persisted request; evaluation, match and any action authorization are separate persisted facts."));
    }

    @GetMapping("/runtime")
    public Map<String, Object> runtime(@PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.min(Math.max(limit, 1), 200);
        var result = new LinkedHashMap<String, Object>();
        result.put("timers", jdbc.queryForList("""
            SELECT timer_id,kind,rule_version_id,condition_id,scope_device_id,generation,due_at,status,skipped_slots,invalidation_reason,consumed_at,consumption_fact_id
            FROM rule_timers WHERE rule_id=? ORDER BY created_at DESC LIMIT ?""", id, bounded));
        result.put("temporalState", jdbc.queryForList("""
            SELECT rule_version_id,condition_id,scope_device_id,generation,operator,phase,armed_at,due_at,coverage_end,jsonb_array_length(members) AS member_count,sequence,reset_reason,updated_at
            FROM rule_temporal_state WHERE rule_id=? ORDER BY updated_at DESC LIMIT ?""", id, bounded));
        result.put("temporalEvidence", temporal.evidenceFor(id, bounded));
        result.put("candidates", jdbc.queryForList("""
            SELECT c.candidate_id,c.match_id,c.action_ordinal,c.action_type,c.target_key,c.priority,c.disposition,c.reason,c.winner_candidate_id,p.fact_id
            FROM rule_action_candidates c JOIN rule_action_plans p USING(plan_id) WHERE c.rule_id=? ORDER BY p.decided_at DESC LIMIT ?""", id, bounded));
        result.put("matches", jdbc.queryForList("""
            SELECT match_id,trigger_type,trigger_fact_id,event_id,rule_version_id,rule_revision,execution_epoch,matched_at
            FROM rule_matches WHERE rule_id=? ORDER BY matched_at DESC LIMIT ?""", id, bounded));
        return result;
    }
}
