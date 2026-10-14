-- V33: restart-safe numeric hysteresis latch (C14, scenario 58).
-- Owner: the logical rule + the stable conditionId + the resolved device scope. generation is the rule's execution epoch: pause, resume
-- and activation of a new version advance it, which starts every latch unlatched WITHOUT writing a synthetic transition.
-- Writers: RuleMatcher (after the frozen evaluation) through RuleHysteresisStore. A write carries the observation time of the evidence it
-- came from; an older observation can never overwrite a newer one (cross-instance safe, no lock beyond the row upsert).
-- Readers: RuleEvaluationSnapshotService, inside the REPEATABLE READ snapshot, only rows of the current generation.
-- UNKNOWN / stale / non-GOOD evidence writes nothing, so it can neither set nor clear a latch.
CREATE TABLE rule_hysteresis_state (
    rule_id         uuid        NOT NULL,
    condition_id    varchar(64) NOT NULL,
    scope_device_id uuid        NOT NULL,
    generation      bigint      NOT NULL CHECK (generation >= 0),
    latched         boolean     NOT NULL,
    observed_at     timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    PRIMARY KEY (rule_id, condition_id, scope_device_id)
);
