-- V28: durable temporal runtime state and inspectable evidence (C15).
-- Identity is ruleVersionId + conditionId + resolved scope + runtime generation (the execution epoch), so progress made by an
-- old version, an old epoch (pause/resume) or another scope can never satisfy a different one.
-- Writers: RuleTemporalRuntime (observation and due processing, one transaction each). Consumers: snapshot service,
-- RuleEvaluator (read-only, frozen), evidence API. V1-V27 are untouched.

CREATE TABLE rule_temporal_state (
    rule_id         uuid NOT NULL,
    rule_version_id uuid NOT NULL,
    rule_revision   bigint NOT NULL,
    condition_id    varchar(64) NOT NULL,
    scope_device_id uuid NOT NULL,
    generation      bigint NOT NULL CHECK (generation >= 0),
    operator        varchar(20) NOT NULL CHECK (operator IN ('FOR_DURATION','COUNT_WITHIN','ABSENCE_FOR','OFFLINE_FOR','TREND')),
    phase           varchar(12) NOT NULL CHECK (phase IN ('IDLE','ARMED','SATISFIED')),
    armed_at        timestamptz,
    due_at          timestamptz,
    coverage_end    timestamptz,
    members         jsonb NOT NULL DEFAULT '[]'::jsonb,
    watermark       jsonb NOT NULL DEFAULT '{}'::jsonb,
    sequence        bigint NOT NULL DEFAULT 0 CHECK (sequence >= 0),
    reset_reason    varchar(80),
    updated_at      timestamptz NOT NULL,
    PRIMARY KEY (rule_version_id, condition_id, scope_device_id, generation),
    CONSTRAINT temporal_state_version FOREIGN KEY (rule_id, rule_version_id, rule_revision)
        REFERENCES rule_versions(rule_id, version_id, revision) ON DELETE RESTRICT,
    CONSTRAINT temporal_state_bounded CHECK (jsonb_array_length(members) <= 200)
);
CREATE INDEX rule_temporal_rule ON rule_temporal_state(rule_id, generation);

CREATE TABLE rule_temporal_evidence (
    seq             bigserial PRIMARY KEY,
    rule_id         uuid NOT NULL,
    rule_version_id uuid NOT NULL,
    condition_id    varchar(64) NOT NULL,
    scope_device_id uuid NOT NULL,
    generation      bigint NOT NULL,
    event           varchar(40) NOT NULL,
    occurred_at     timestamptz NOT NULL,
    details         jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX rule_temporal_evidence_state ON rule_temporal_evidence(rule_version_id, condition_id, scope_device_id, generation, seq DESC);
