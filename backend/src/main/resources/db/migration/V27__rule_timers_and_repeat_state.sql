-- V27: Rule-owned durable timers (C18) and edge-latch repeat state (C14). Not shared with Twin verification deadlines.
-- Writers: RuleTimerService (arming, invalidation, consumption in one transaction with the source fact),
-- RuleRepeatStateStore. Consumers: RuleTimerWorker, RuleMatchPersister. V1-V26 are untouched.

CREATE TABLE rule_timers (
    timer_id        uuid PRIMARY KEY,
    kind            varchar(20) NOT NULL CHECK (kind IN ('SCHEDULE','TEMPORAL_DUE')),
    rule_id         uuid NOT NULL,
    rule_version_id uuid NOT NULL,
    rule_revision   bigint NOT NULL,
    condition_id    varchar(64),
    scope_device_id uuid NOT NULL,
    generation      bigint NOT NULL CHECK (generation >= 0),
    due_at          timestamptz NOT NULL,
    status          varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','INVALIDATED','CONSUMED')),
    watermark       jsonb NOT NULL DEFAULT '{}'::jsonb,
    skipped_slots   bigint NOT NULL DEFAULT 0 CHECK (skipped_slots >= 0),
    invalidation_reason varchar(60),
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    consumed_at     timestamptz,
    consumption_fact_id uuid REFERENCES rule_trigger_facts(fact_id) ON DELETE RESTRICT,
    CONSTRAINT timer_version FOREIGN KEY (rule_id, rule_version_id, rule_revision) REFERENCES rule_versions(rule_id, version_id, revision) ON DELETE RESTRICT,
    CONSTRAINT timer_consumed_evidence CHECK ((status = 'CONSUMED') = (consumed_at IS NOT NULL)),
    CONSTRAINT timer_condition CHECK ((kind = 'TEMPORAL_DUE') = (condition_id IS NOT NULL))
);
-- Bounded discovery of due work; pending rows only.
CREATE INDEX rule_timer_due ON rule_timers(due_at) WHERE status = 'PENDING';
CREATE INDEX rule_timer_owner ON rule_timers(rule_id, status);
-- At most one pending timer per schedule or temporal condition runtime identity.
CREATE UNIQUE INDEX rule_timer_pending_identity ON rule_timers(rule_version_id, kind, COALESCE(condition_id, ''), scope_device_id, generation)
    WHERE status = 'PENDING';

-- EDGE_TRUE latch: logical-rule scoped, reset when the execution epoch (generation) changes.
CREATE TABLE rule_repeat_state (
    rule_id         uuid NOT NULL,
    scope_device_id uuid NOT NULL,
    generation      bigint NOT NULL,
    latched         boolean NOT NULL,
    updated_at      timestamptz NOT NULL,
    PRIMARY KEY (rule_id, scope_device_id)
);
