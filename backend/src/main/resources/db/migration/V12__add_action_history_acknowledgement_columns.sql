-- V12__add_action_history_acknowledgement_columns.sql
--
-- Tier 5E: adds command-acknowledgement tracking to the existing
-- action_history row rather than introducing a new table. These columns
-- are only ever populated for SEND_COMMAND rows that have received an
-- MQTT ACK from the device - every other row (webhook/create-alert
-- actions, or a SEND_COMMAND row awaiting/never receiving an ACK) leaves
-- them NULL, exactly mirroring the existing nullable match_id precedent
-- (V11) for "not applicable to this row".

ALTER TABLE action_history ADD COLUMN ack_status VARCHAR(50);
ALTER TABLE action_history ADD COLUMN ack_received_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE action_history ADD COLUMN ack_message VARCHAR(255);
