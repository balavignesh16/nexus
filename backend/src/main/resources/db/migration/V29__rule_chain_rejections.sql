-- V29: inspectable evidence for rejected rule-chain work (C17): maximum depth 8 and no repeated logical rule in a causation chain.
-- Writer: RuleMatchPersister / RuleMatcher in the rejecting transaction. Consumer: rule runtime inspection API. V1-V28 untouched.
CREATE TABLE rule_chain_rejections (
    rejection_id    uuid PRIMARY KEY,
    parent_match_id uuid REFERENCES rule_matches(match_id) ON DELETE RESTRICT,
    fact_id         uuid REFERENCES rule_trigger_facts(fact_id) ON DELETE RESTRICT,
    rule_id         uuid NOT NULL,
    reason          varchar(30) NOT NULL CHECK (reason IN ('MAX_DEPTH','CYCLE')),
    chain_depth     integer NOT NULL,
    causation_rule_ids uuid[] NOT NULL DEFAULT '{}',
    rejected_at     timestamptz NOT NULL
);
CREATE INDEX rule_chain_rejection_rule ON rule_chain_rejections(rule_id, rejected_at DESC);
