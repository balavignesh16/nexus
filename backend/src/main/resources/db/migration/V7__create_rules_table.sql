-- V7__create_rules_table.sql

CREATE TABLE rules (
    rule_id    UUID PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    enabled    BOOLEAN NOT NULL,
    priority   INTEGER NOT NULL,
    conditions JSONB NOT NULL,
    actions    JSONB NOT NULL,
    metadata   JSONB NOT NULL DEFAULT '{}'::jsonb
);
