-- V32: durable, serialized cooldown authority (C14, scenarios 55/56).
-- Owner: the logical rule (never a RuleVersion). scope_key '' is the global per-logical-rule cooldown that imported rules keep;
-- a resolved device id is used only by rules that explicitly opt into PER_SCOPE.
-- Writers: RuleMatchPersister, inside the REQUIRES_NEW transaction that persists the RuleMatch (the row is locked FOR UPDATE for the
-- check and the update, so competing instances serialize and a failed match persistence rolls the cooldown back with it).
-- Readers: RuleMatcher pre-check (non-locking), restart hydration is no longer needed.
-- Lock order: rules row (shared eligibility read) -> cooldown row -> repeat-state row.
-- Rows are never purged by retention (cooldown authority must survive policy changes); at most one row per rule and scope.

CREATE TABLE rule_cooldown_state (
    rule_id         uuid        NOT NULL,
    scope_key       varchar(64) NOT NULL,
    last_matched_at timestamptz,
    last_match_id   uuid REFERENCES rule_matches(match_id) ON DELETE RESTRICT,
    updated_at      timestamptz NOT NULL,
    PRIMARY KEY (rule_id, scope_key),
    CONSTRAINT cooldown_match_pair CHECK ((last_matched_at IS NULL) = (last_match_id IS NULL))
);

-- Historical reconciliation: the latest repeat-eligible persisted match of each logical rule becomes its global cooldown authority.
-- Exactly what RuleMatcher used to hydrate in memory at startup (cooldown_eligible matches only), now durable.
INSERT INTO rule_cooldown_state(rule_id, scope_key, last_matched_at, last_match_id, updated_at)
SELECT DISTINCT ON (rule_id) rule_id, '', matched_at, match_id, now()
FROM rule_matches
WHERE cooldown_eligible
ORDER BY rule_id, matched_at DESC, match_id DESC;

-- EDGE_TRUE latch (V27) gains the observation time that last changed it, so a late, older "definitely false" observation applied by
-- another instance can never rearm an edge that a newer true observation already consumed. Existing rows keep NULL (= unknown).
ALTER TABLE rule_repeat_state ADD COLUMN latched_at timestamptz;
