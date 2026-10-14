-- V26: durable typed trigger facts (C12/C13) and persisted action-plan authority (C16 minimum).
-- Writers: each source transaction inserts its fact (ON CONFLICT DO NOTHING deduplicates); RuleTriggerRouter
-- reads a fact only after that transaction committed. Consumers: RuleTriggerRouter, RuleMatchPersister,
-- RuleActionPlanner, RuleExecutionAuthorization. V1-V25 are untouched.

CREATE TABLE rule_trigger_facts (
    fact_id         uuid PRIMARY KEY,
    trigger_type    varchar(40) NOT NULL CHECK (trigger_type IN (
        'ACCEPTED_TELEMETRY','TWIN_STATE_CHANGED','CONNECTIVITY_CHANGED','COMMAND_PUBLICATION_FAILED',
        'COMMAND_ACK_REJECTED','COMMAND_ACK_TIMED_OUT','VERIFICATION_CHANGED','VERIFICATION_TIMED_OUT',
        'MANUAL','SCHEDULE','TEMPORAL_DUE','RULE_MATCHED')),
    source_key      varchar(255) NOT NULL,
    device_id       uuid,
    occurred_at     timestamptz NOT NULL,
    recorded_at     timestamptz NOT NULL,
    source_ref      jsonb NOT NULL DEFAULT '{}'::jsonb,
    attributes      jsonb NOT NULL DEFAULT '{}'::jsonb,
    correlation_id  varchar(255),
    parent_match_id uuid,
    root_fact_id    uuid,
    chain_depth     integer NOT NULL DEFAULT 0 CHECK (chain_depth BETWEEN 0 AND 8),
    causation_rule_ids uuid[] NOT NULL DEFAULT '{}',
    CONSTRAINT rule_fact_identity UNIQUE (trigger_type, source_key),
    CONSTRAINT rule_fact_chain CHECK ((trigger_type = 'RULE_MATCHED') = (parent_match_id IS NOT NULL))
);
CREATE INDEX rule_fact_device_time ON rule_trigger_facts(trigger_type, device_id, occurred_at DESC);

ALTER TABLE rule_matches
    ALTER COLUMN event_id DROP NOT NULL,
    ADD COLUMN trigger_type varchar(40),
    ADD COLUMN trigger_fact_id uuid REFERENCES rule_trigger_facts(fact_id) ON DELETE RESTRICT,
    ADD CONSTRAINT match_trigger_source CHECK (
        (event_id IS NOT NULL AND trigger_fact_id IS NULL AND (trigger_type IS NULL OR trigger_type = 'LEGACY_DOMAIN_EVENT')) OR
        (event_id IS NULL AND trigger_fact_id IS NOT NULL AND trigger_type IS NOT NULL AND trigger_type <> 'LEGACY_DOMAIN_EVENT'));
-- One decision per (logical rule, immutable version, source fact): concurrent routers cannot duplicate it.
CREATE UNIQUE INDEX match_fact_decision ON rule_matches(rule_id, rule_version_id, trigger_fact_id) WHERE trigger_fact_id IS NOT NULL;
ALTER TABLE rule_trigger_facts ADD CONSTRAINT rule_fact_parent FOREIGN KEY (parent_match_id) REFERENCES rule_matches(match_id) ON DELETE RESTRICT;

-- Persisted connectivity transition identity: a generation per committed status change.
ALTER TABLE device_connectivity ADD COLUMN generation bigint NOT NULL DEFAULT 0;

-- One arbitrated plan per source fact (batch); candidates carry the pre-assigned command request id.
CREATE TABLE rule_action_plans (
    plan_id     uuid PRIMARY KEY,
    fact_id     uuid NOT NULL UNIQUE REFERENCES rule_trigger_facts(fact_id) ON DELETE RESTRICT,
    decided_at  timestamptz NOT NULL,
    status      varchar(20) NOT NULL CHECK (status IN ('APPROVED','NO_CANDIDATES'))
);
CREATE TABLE rule_action_candidates (
    candidate_id    uuid PRIMARY KEY,
    plan_id         uuid NOT NULL REFERENCES rule_action_plans(plan_id) ON DELETE RESTRICT,
    match_id        uuid NOT NULL REFERENCES rule_matches(match_id) ON DELETE RESTRICT,
    rule_id         uuid NOT NULL,
    rule_version_id uuid NOT NULL,
    rule_revision   bigint NOT NULL,
    action_ordinal  integer NOT NULL CHECK (action_ordinal >= 0),
    action_type     varchar(40) NOT NULL,
    target_key      text,
    priority        integer NOT NULL,
    request_id      uuid NOT NULL UNIQUE,
    disposition     varchar(30) NOT NULL CHECK (disposition IN ('SELECTED','COALESCED','SUPPRESSED_PRIORITY','SUPPRESSED_CONFLICT')),
    reason          text NOT NULL,
    winner_candidate_id uuid,
    parameters      jsonb NOT NULL,
    CONSTRAINT candidate_identity UNIQUE (plan_id, rule_id, action_ordinal),
    CONSTRAINT candidate_version FOREIGN KEY (rule_id, rule_version_id, rule_revision) REFERENCES rule_versions(rule_id, version_id, revision) ON DELETE RESTRICT
);
CREATE INDEX candidate_match ON rule_action_candidates(match_id);
