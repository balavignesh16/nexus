-- V8__create_rule_matches_table.sql

-- Durable record of a rule-match outcome (Tier 3 Phase 9A). A normal
-- PostgreSQL table, deliberately not a TimescaleDB hypertable, matching the
-- existing plain-table convention used by domain_events and action_history
-- rather than the hypertable convention used by telemetry_history/pipeline_traces.
CREATE TABLE rule_matches (
    match_id            UUID PRIMARY KEY,
    rule_id             UUID NOT NULL,
    rule_snapshot       JSONB NOT NULL,
    event_id            UUID NOT NULL,
    correlation_id      VARCHAR(255),
    evaluation_evidence JSONB NOT NULL,
    matched_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    message             TEXT
);

-- The action_history.match_id -> rule_matches.match_id foreign key is
-- deliberately NOT added in this migration (Tier 3 Phase 9A). Adding it now
-- would fail against real databases already holding action_history rows
-- with match_id values that predate rule_matches, and would break the
-- currently-working rule-match -> action-history pipeline, since nothing
-- populates rule_matches yet (that is Phase 9B). The FK is deferred to
-- Phase 9C, once Phase 9B has wired durable rule-match persistence into
-- RuleMatcher and the producer/consumer relationship actually holds.
-- action_history is otherwise untouched by this migration.
