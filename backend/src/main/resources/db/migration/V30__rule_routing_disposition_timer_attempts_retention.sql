-- V30: durable routing disposition for typed facts, bounded timer retry/quarantine, and purge support (forensic corrections H1/H2/H4).
-- Forward-only: V1-V29 are untouched. Rows that existed before this migration are marked ROUTED so an upgrade never replays history.
-- Writers: RuleTriggerRouter (disposition), RuleFactRecoveryScanner (retry), RuleTimerService (attempts/quarantine), RuleRetentionService (purge).

ALTER TABLE rule_trigger_facts
    ADD COLUMN routing_status varchar(12) NOT NULL DEFAULT 'ROUTED'
        CONSTRAINT rule_fact_routing_status CHECK (routing_status IN ('PENDING','ROUTED','FAILED','QUARANTINED')),
    ADD COLUMN routing_attempts integer NOT NULL DEFAULT 0 CHECK (routing_attempts >= 0),
    ADD COLUMN routing_error text,
    ADD COLUMN routed_at timestamptz,
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN temporal_applied_at timestamptz;
ALTER TABLE rule_trigger_facts ALTER COLUMN routing_status SET DEFAULT 'PENDING';
CREATE INDEX rule_fact_unrouted ON rule_trigger_facts(recorded_at) WHERE routing_status IN ('PENDING','FAILED');
CREATE INDEX rule_fact_purge ON rule_trigger_facts(recorded_at) WHERE routing_status IN ('ROUTED','QUARANTINED');
CREATE INDEX rule_fact_device_stream ON rule_trigger_facts(device_id, recorded_at) WHERE trigger_type IN ('ACCEPTED_TELEMETRY','CONNECTIVITY_CHANGED');

ALTER TABLE rule_timers
    ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    ADD COLUMN last_error text,
    ADD COLUMN last_attempt_at timestamptz,
    ADD COLUMN next_attempt_at timestamptz;
ALTER TABLE rule_timers DROP CONSTRAINT IF EXISTS rule_timers_status_check;
ALTER TABLE rule_timers ADD CONSTRAINT rule_timers_status_check CHECK (status IN ('PENDING','INVALIDATED','CONSUMED','QUARANTINED'));
CREATE INDEX rule_timer_terminal_age ON rule_timers(updated_at) WHERE status <> 'PENDING';

-- Subscription lookups for "does any active version need this fact" (jsonb containment on the immutable definition).
CREATE INDEX rule_versions_definition_gin ON rule_versions USING gin (definition jsonb_path_ops);
CREATE INDEX rule_temporal_evidence_age ON rule_temporal_evidence(occurred_at);
