package com.nexus.rule.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Persisted arbitration evidence for one RuleMatch (C16/C20): every candidate of the plan(s) the match took part in, with its
 * disposition, reason, winner, target, priority, exclusion membership and what guarded acceptance / the executor did with it (outcome), with
 * the ActionHistory status, publication time and ACK status shown as SEPARATE facts and never inferred from one another. This is read-only history;
 * a plan being APPROVED is a decision, never proof of command acceptance, publication, ACK, convergence or physical actuation.
 */
@RestController
@RequestMapping("/api/v1/rule-matches/history/{matchId}/arbitration")
public class RuleArbitrationController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public RuleArbitrationController(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @GetMapping
    public Map<String, Object> arbitration(@PathVariable UUID matchId) {
        var plans = jdbc.queryForList("SELECT DISTINCT p.plan_id,p.status,p.decided_at,p.fact_id,p.event_id FROM rule_action_plans p JOIN rule_action_candidates c ON c.plan_id=p.plan_id WHERE c.match_id=?", matchId);
        var result = new LinkedHashMap<String, Object>();
        result.put("matchId", matchId);
        var out = new ArrayList<Map<String, Object>>();
        for (var plan : plans) {
            var view = new LinkedHashMap<String, Object>();
            view.put("planId", plan.get("plan_id"));
            view.put("status", plan.get("status"));
            view.put("decidedAt", String.valueOf(plan.get("decided_at")));
            view.put("source", plan.get("fact_id") != null ? Map.of("kind", "FACT", "id", plan.get("fact_id")) : Map.of("kind", "EVENT", "id", plan.get("event_id")));
            var candidates = new ArrayList<Map<String, Object>>();
            for (var row : jdbc.queryForList("""
                    SELECT c.candidate_id,c.match_id,c.rule_id,c.rule_version_id,c.rule_revision,c.action_ordinal,c.action_type,c.target_key,c.priority,c.disposition,c.reason,
                           c.winner_candidate_id,c.request_id,c.parameters::text AS parameters,c.evidence::text AS evidence,c.outcome,c.outcome_reason,c.outcome_at,
                           h.status AS history_status,h.published_at,h.ack_status
                    FROM rule_action_candidates c LEFT JOIN action_history h ON h.request_id=c.request_id
                    WHERE c.plan_id=? ORDER BY c.priority,c.rule_id,c.rule_revision,c.action_ordinal""", plan.get("plan_id"))) {
                var candidate = new LinkedHashMap<String, Object>(row);
                candidate.put("parameters", json(row.get("parameters")));
                candidate.put("evidence", json(row.get("evidence")));
                candidate.put("outcome_at", row.get("outcome_at") == null ? null : String.valueOf(row.get("outcome_at")));
                candidate.put("published_at", row.get("published_at") == null ? null : String.valueOf(row.get("published_at")));
                candidates.add(candidate);
            }
            view.put("candidates", candidates);
            out.add(view);
        }
        if (out.isEmpty() && !jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rule_matches WHERE match_id=?)", Boolean.class, matchId))
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "RuleMatch not found");
        result.put("plans", out);
        result.put("note", "A plan is a decision. Command acceptance, MQTT publication, device ACK, qualified readback and physical actuation are separate facts.");
        return result;
    }

    private Object json(Object text) {
        try { return text == null ? null : mapper.readValue(text.toString(), Object.class); }
        catch (Exception unreadable) { return text; }
    }
}
