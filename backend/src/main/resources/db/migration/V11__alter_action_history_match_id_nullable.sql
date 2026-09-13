-- V11__alter_action_history_match_id_nullable.sql
--
-- Tier 5D: a manually-triggered command (dispatched directly via
-- POST /devices/{deviceId}/commands, not through a rule match) genuinely
-- has no RuleMatch to reference. match_id becomes nullable rather than
-- fabricating a fake RuleMatch/UUID to satisfy a NOT NULL constraint that
-- no longer reflects reality for every row.

ALTER TABLE action_history ALTER COLUMN match_id DROP NOT NULL;
