-- V35: truthful candidate execution outcomes (forensic finding F4). An ActionHistory row existing says only that an executor was invoked;
-- it never proves acceptance, success, publication, ACK or actuation. The candidate outcome vocabulary therefore distinguishes:
--   ACCEPTED         a command passed guarded acceptance and its DESIRED_STATE_CHANGED transition is durable (publication, ACK and convergence
--                    are separate P2.4 facts, read from action_history / verification state, never inferred here)
--   EXECUTED         a non-command executor reported SUCCESS (stub executors prove nothing outside the system)
--   EXECUTION_FAILED the executor ran and reported FAILURE, or a command was rejected before it was accepted
--   UNSUPPORTED / IGNORED   the executor said so
--   DENIED           guarded acceptance refused the request (nothing was written)
-- Writers: RuleActionPlanStore.recordOutcomes and RuleCandidateOutcomes. NULL = nothing is known (for example a crash before dispatch).
ALTER TABLE rule_action_candidates DROP CONSTRAINT candidate_outcome;
ALTER TABLE rule_action_candidates ADD CONSTRAINT candidate_outcome CHECK (outcome IS NULL OR
    outcome IN ('ACCEPTED','EXECUTED','EXECUTION_FAILED','UNSUPPORTED','IGNORED','DENIED'));

-- Historical re-derivation: V34 marked every candidate that merely had an ActionHistory row as ACCEPTED, including failed and unsupported
-- executions. Re-derive each from the evidence; whatever cannot be proven becomes NULL (unknown) rather than a fabricated success.
UPDATE rule_action_candidates c SET
    outcome = CASE
        WHEN c.action_type = 'SEND_COMMAND' AND EXISTS (SELECT 1 FROM twin_state_transitions t WHERE t.transition_type = 'DESIRED_STATE_CHANGED' AND t.command_id = c.request_id) THEN 'ACCEPTED'
        WHEN c.action_type <> 'SEND_COMMAND' AND h.status = 'SUCCESS' THEN 'EXECUTED'
        WHEN h.status = 'FAILURE' THEN 'EXECUTION_FAILED'
        WHEN h.status = 'UNSUPPORTED' THEN 'UNSUPPORTED'
        WHEN h.status = 'IGNORED' THEN 'IGNORED'
        ELSE NULL END,
    outcome_reason = CASE WHEN h.status IN ('FAILURE', 'UNSUPPORTED', 'IGNORED') THEN left(h.message, 500) ELSE NULL END
FROM action_history h
WHERE h.request_id = c.request_id AND c.outcome = 'ACCEPTED' AND c.disposition = 'SELECTED';
