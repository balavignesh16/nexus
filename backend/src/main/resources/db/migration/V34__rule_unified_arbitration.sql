-- V34: one persisted arbitration pathway for typed AND legacy matches, mutual-exclusion groups and candidate evidence (P3.5, C13 retirement).
--
-- Plans: a plan decides the actions of ONE trigger batch. Typed batches are keyed by their committed fact (V26); legacy DomainEvent batches
-- are now keyed by the persisted event, so legacy actions pass through the same candidates -> arbitration -> approved plan -> guarded
-- acceptance flow. Exactly one source is set; each source has at most one plan (a re-delivered batch is reused, never re-decided).
-- Existing rows keep their fact source unchanged.
ALTER TABLE rule_action_plans ALTER COLUMN fact_id DROP NOT NULL;
ALTER TABLE rule_action_plans ADD COLUMN event_id uuid;
ALTER TABLE rule_action_plans ADD CONSTRAINT plan_single_source CHECK ((fact_id IS NULL) <> (event_id IS NULL));
CREATE UNIQUE INDEX rule_action_plan_event ON rule_action_plans(event_id) WHERE event_id IS NOT NULL;

-- Candidate dispositions gain the mutual-exclusion outcomes; evidence records why (group, rank, scope), and outcome records what
-- guarded acceptance did with a SELECTED candidate when that is known (ACCEPTED = a durable ActionHistory row exists, DENIED =
-- authorization refused it). A plan being APPROVED is a decision, never proof of acceptance, publication or actuation.
ALTER TABLE rule_action_candidates DROP CONSTRAINT IF EXISTS rule_action_candidates_disposition_check;
ALTER TABLE rule_action_candidates ADD CONSTRAINT rule_action_candidates_disposition_check CHECK (disposition IN
    ('SELECTED','COALESCED','SUPPRESSED_PRIORITY','SUPPRESSED_CONFLICT','SUPPRESSED_EXCLUSION','SUPPRESSED_EXCLUSION_CONFLICT'));
ALTER TABLE rule_action_candidates ADD COLUMN evidence jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE rule_action_candidates ADD COLUMN outcome varchar(16);
ALTER TABLE rule_action_candidates ADD COLUMN outcome_reason text;
ALTER TABLE rule_action_candidates ADD COLUMN outcome_at timestamptz;
ALTER TABLE rule_action_candidates ADD CONSTRAINT candidate_outcome CHECK (outcome IS NULL OR outcome IN ('ACCEPTED','DENIED'));

-- Mutual exclusion (C16). A group names a set of logical rules of which, in one trigger batch, at most one rule's COMMAND candidates may
-- act: the explicit integer rank decides (smaller wins), an equal best rank suppresses every competing command (fail closed).
-- Owner: the group. Writers: RuleExclusionGroupService (membership changes lock the group row FOR UPDATE); reader: RuleActionPlanStore,
-- inside the plan transaction, with the group rows FOR SHARE so a configuration change cannot interleave with a decision.
-- Lock order: plan row -> group rows. Members and groups are RESTRICTed so a restriction can only disappear by an explicit delete.
CREATE TABLE rule_exclusion_groups (
    group_id   uuid PRIMARY KEY,
    name       varchar(120) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL
);
CREATE TABLE rule_exclusion_members (
    group_id uuid    NOT NULL REFERENCES rule_exclusion_groups(group_id) ON DELETE RESTRICT,
    rule_id  uuid    NOT NULL REFERENCES rules(rule_id) ON DELETE RESTRICT,
    rank     integer NOT NULL CHECK (rank >= 0),
    PRIMARY KEY (group_id, rule_id)
);
CREATE INDEX rule_exclusion_member_rule ON rule_exclusion_members(rule_id);
