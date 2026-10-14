-- V36: durable, bounded recovery of legacy matches whose ActionPlan was never created (forensic finding F1).
-- A legacy RuleMatch is persisted before its batch's plan (the cooldown and edge are claimed with the match). If the plan write fails the match
-- stays history and nothing would ever dispatch it. RuleLegacyPlanRecoveryScanner discovers such events, rebuilds the batch from PERSISTED
-- evidence only (the committed DomainEvent, the immutable rule version of each match and its pinned revision/epoch), re-runs the same
-- arbitration planner, and never touches cooldown or EDGE_TRUE state. There is no outbox and no resend: an event that already has a plan is
-- never recovered, and a plan creates the only candidates that can ever reach guarded acceptance.
-- One row per legacy event (bounded attempts, backoff and a diagnostic message).
--   PENDING / FAILED : eligible for (another) attempt
--   RECOVERED        : a plan for the event exists
--   EXPIRED          : too old to act on safely, or ownership of every match was lost: recorded, never dispatched
--   UNRECOVERABLE    : the batch cannot be reconstructed truthfully (missing event/version, unattributed match)
--   QUARANTINED      : attempt budget exhausted
--   HISTORICAL       : matched before this mechanism existed (its actions were dispatched by the retired path): never replayed
-- Writers: RuleLegacyPlanRecoveryScanner. Readers: RuleRuntimeController (per-rule diagnostics). Lock order: recovery row -> plan row -> group rows.
CREATE TABLE rule_legacy_plan_recovery (
    event_id        uuid        PRIMARY KEY,
    status          varchar(16) NOT NULL CHECK (status IN ('PENDING','FAILED','RECOVERED','EXPIRED','UNRECOVERABLE','QUARANTINED','HISTORICAL')),
    attempts        integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    reason          text,
    last_error      text,
    next_attempt_at timestamptz,
    discovered_at   timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);
CREATE INDEX rule_legacy_plan_recovery_due ON rule_legacy_plan_recovery(next_attempt_at) WHERE status IN ('PENDING','FAILED');

-- Upgrade safety: every legacy match that exists now was dispatched (or not) by the retired direct path. None may ever be replayed.
INSERT INTO rule_legacy_plan_recovery(event_id, status, reason, discovered_at, updated_at)
SELECT DISTINCT m.event_id, 'HISTORICAL', 'PRE_RECOVERY_MATCH', now(), now()
FROM rule_matches m
WHERE m.event_id IS NOT NULL AND m.trigger_fact_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM rule_action_plans p WHERE p.event_id = m.event_id);
