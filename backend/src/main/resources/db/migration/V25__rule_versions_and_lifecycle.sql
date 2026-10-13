-- Executable history is separate from the mutable compatibility projection.
ALTER TABLE rules
    ADD COLUMN lifecycle varchar(16) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN row_revision bigint NOT NULL DEFAULT 0 CHECK (row_revision >= 0),
    ADD COLUMN execution_epoch bigint NOT NULL DEFAULT 0 CHECK (execution_epoch >= 0),
    ADD COLUMN active_version_id uuid,
    ADD COLUMN working_version_id uuid,
    ADD COLUMN original_enabled boolean,
    ADD COLUMN reconciliation varchar(24) NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN restoration_eligible boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT rule_lifecycle CHECK (lifecycle IN ('DRAFT','VALIDATED','ACTIVE','PAUSED','ARCHIVED')),
    ADD CONSTRAINT rule_reconciliation CHECK (reconciliation IN ('NOT_REQUIRED','PENDING','PASSED','FAILED','REQUIRES_REVIEW'));

CREATE TABLE rule_versions (
    version_id uuid PRIMARY KEY,
    rule_id uuid NOT NULL REFERENCES rules(rule_id) ON DELETE RESTRICT,
    revision bigint NOT NULL CHECK (revision > 0),
    definition jsonb NOT NULL CHECK (jsonb_typeof(definition) = 'object'),
    definition_format integer NOT NULL DEFAULT 1 CHECK (definition_format = 1),
    created_at timestamptz NOT NULL,
    provenance varchar(32) NOT NULL,
    UNIQUE (rule_id, revision),
    UNIQUE (rule_id, version_id),
    UNIQUE (rule_id, version_id, revision)
);
CREATE TABLE rule_version_workflow (
    version_id uuid PRIMARY KEY REFERENCES rule_versions(version_id) ON DELETE RESTRICT,
    state varchar(16) NOT NULL CHECK (state IN ('DRAFT','VALIDATED'))
);
CREATE TABLE rule_validation_attempts (
    attempt_id uuid PRIMARY KEY,
    attempt_order bigserial NOT NULL UNIQUE,
    rule_id uuid NOT NULL,
    version_id uuid NOT NULL,
    purpose varchar(24) NOT NULL,
    valid boolean NOT NULL,
    diagnostics jsonb NOT NULL,
    validated_at timestamptz NOT NULL,
    FOREIGN KEY (rule_id, version_id) REFERENCES rule_versions(rule_id, version_id) ON DELETE RESTRICT
);
ALTER TABLE rules
    ADD CONSTRAINT rule_active_owner FOREIGN KEY (rule_id, active_version_id)
        REFERENCES rule_versions(rule_id, version_id) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT rule_working_owner FOREIGN KEY (rule_id, working_version_id)
        REFERENCES rule_versions(rule_id, version_id) DEFERRABLE INITIALLY DEFERRED;

UPDATE rules SET original_enabled = enabled, restoration_eligible = enabled,
    lifecycle = 'PAUSED', reconciliation = 'PENDING', enabled = false,
    working_version_id = gen_random_uuid();
INSERT INTO rule_versions(version_id, rule_id, revision, definition, created_at, provenance)
SELECT working_version_id, rule_id, 1,
    jsonb_build_object('ruleId',rule_id,'name',name,'enabled',false,'priority',priority,
        'conditions',conditions,'actions',actions,'metadata',metadata,'cooldownSeconds',cooldown_seconds),
    CURRENT_TIMESTAMP, 'V24_IMPORT'
FROM rules;
INSERT INTO rule_version_workflow SELECT version_id, 'DRAFT' FROM rule_versions;

-- Flush deferred ownership checks before subsequent DDL on populated rules.
SET CONSTRAINTS rule_active_owner, rule_working_owner IMMEDIATE;

ALTER TABLE rules ADD CONSTRAINT rule_active_eligibility CHECK (
    (lifecycle = 'ACTIVE') = enabled AND
    (lifecycle <> 'ACTIVE' OR (active_version_id IS NOT NULL AND reconciliation IN ('PASSED','NOT_REQUIRED')))
);
ALTER TABLE rule_matches
    ADD COLUMN cooldown_eligible boolean NOT NULL DEFAULT true,
    ADD COLUMN rule_version_id uuid,
    ADD COLUMN rule_revision bigint,
    ADD COLUMN execution_epoch bigint,
    ADD CONSTRAINT match_version_attribution CHECK (
        (rule_version_id IS NULL AND rule_revision IS NULL AND execution_epoch IS NULL) OR
        (rule_version_id IS NOT NULL AND rule_revision IS NOT NULL AND rule_revision > 0 AND execution_epoch IS NOT NULL AND execution_epoch >= 0)
    ),
    ADD CONSTRAINT match_version_owner FOREIGN KEY (rule_id, rule_version_id, rule_revision)
        REFERENCES rule_versions(rule_id, version_id, revision) ON DELETE RESTRICT;
CREATE INDEX rule_active_versions ON rules(active_version_id) WHERE lifecycle = 'ACTIVE';
CREATE INDEX rule_pending_reconciliation ON rules(rule_id) WHERE reconciliation = 'PENDING';
CREATE INDEX rule_validation_history ON rule_validation_attempts(rule_id, attempt_order DESC);
CREATE INDEX match_version_history ON rule_matches(rule_version_id, matched_at DESC);

CREATE FUNCTION reject_rule_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; % is prohibited', TG_TABLE_NAME, TG_OP;
END;
$$;
CREATE TRIGGER immutable_rule_versions BEFORE UPDATE OR DELETE ON rule_versions
    FOR EACH ROW EXECUTE FUNCTION reject_rule_history_mutation();
CREATE TRIGGER append_only_rule_validation BEFORE UPDATE OR DELETE ON rule_validation_attempts
    FOR EACH ROW EXECUTE FUNCTION reject_rule_history_mutation();
