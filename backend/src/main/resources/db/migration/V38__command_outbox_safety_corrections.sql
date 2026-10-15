-- V38__command_outbox_safety_corrections.sql
--
-- Phase 4 P4.1 safety corrections (forensic review findings F1 and F7). Forward-only: V37 is not modified.
-- Nothing here reads, backfills or publishes any command; the outbox is still not used by production dispatch.
--
-- F1. Device deletion must never erase a delivery obligation.
--   V37 made command_outbox.device_id ON DELETE CASCADE and its delete guard allowed the cascade, so deleting a device
--   silently erased QUEUED, IN_FLIGHT and RETRY_WAIT obligations (and resolved rows) while their action_history rows stayed
--   ACCEPTED_FOR_DISPATCH with a null device: an outstanding command disappeared without any terminal record.
--
--   Policy (fail closed, enforced by the database so every deletion path is covered):
--     * A device with any QUEUED, IN_FLIGHT or RETRY_WAIT obligation cannot be deleted.
--     * A device with any outbox row at all cannot be deleted either, because the evidence mirror that would make deleting
--       resolved rows safe (a terminal fact in a durable state history that survives device removal) does not exist yet.
--       Resolved rows leave through the retention purge (RetentionManager, nexus.retention.actions-days); once they are gone
--       the device can be deleted. A later migration may relax this to "unresolved only" once the state history exists.
--     * Refusal changes nothing: the device, its action_history rows, desired state and verification evidence are untouched.
--   Mechanism: the foreign key becomes ON DELETE RESTRICT (race-safe: a concurrent insert holds a key-share lock on the device
--   row), and a BEFORE DELETE trigger on devices raises a distinct, stable message for each case so the application can
--   translate it into a typed rejection.
--
-- F7. Attempt exhaustion and expiry at claim time.
--   * A RETRY_WAIT row must still have an attempt left, so an exhausted row cannot be parked (it must become DEAD_LETTER).
--   * A row cannot be claimed (-> IN_FLIGHT) at or after its expires_at. This is a database backstop only: the dispatcher of
--     P4.2 must still select only unexpired rows and expire the rest (contract R11, R12); nothing here is a dispatcher.

ALTER TABLE command_outbox DROP CONSTRAINT command_outbox_device_id_fkey;
ALTER TABLE command_outbox ADD CONSTRAINT fk_command_outbox_device
    FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE RESTRICT;

CREATE FUNCTION devices_guard_command_obligations() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM command_outbox WHERE device_id = OLD.id AND state IN ('QUEUED', 'IN_FLIGHT', 'RETRY_WAIT')) THEN
        RAISE EXCEPTION 'DEVICE_HAS_UNRESOLVED_COMMANDS: device % has command delivery obligations that are not resolved', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM command_outbox WHERE device_id = OLD.id) THEN
        RAISE EXCEPTION 'DEVICE_HAS_COMMAND_DELIVERY_EVIDENCE: device % still has command delivery records that retention has not purged', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_devices_guard_command_obligations BEFORE DELETE ON devices
    FOR EACH ROW EXECUTE FUNCTION devices_guard_command_obligations();

ALTER TABLE command_outbox ADD CONSTRAINT ck_outbox_retry_has_attempts_left
    CHECK (state <> 'RETRY_WAIT' OR attempt_count < max_attempts);

CREATE OR REPLACE FUNCTION command_outbox_guard_update() RETURNS trigger AS $$
BEGIN
    IF NEW.command_id IS DISTINCT FROM OLD.command_id
       OR NEW.device_id IS DISTINCT FROM OLD.device_id
       OR NEW.topic IS DISTINCT FROM OLD.topic
       OR NEW.payload_text IS DISTINCT FROM OLD.payload_text
       OR NEW.payload_sha256 IS DISTINCT FROM OLD.payload_sha256
       OR NEW.payload_epoch_seconds IS DISTINCT FROM OLD.payload_epoch_seconds
       OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
       OR NEW.max_attempts IS DISTINCT FROM OLD.max_attempts
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'command_outbox identity, payload and delivery bounds are immutable (command %)', OLD.command_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.state IN ('PUBLISHED', 'DEAD_LETTER') THEN
        RAISE EXCEPTION 'command_outbox row % is terminal (%) and immutable', OLD.command_id, OLD.state
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.state <> OLD.state AND NOT (
           (OLD.state = 'QUEUED' AND NEW.state IN ('IN_FLIGHT', 'DEAD_LETTER'))
        OR (OLD.state = 'RETRY_WAIT' AND NEW.state IN ('IN_FLIGHT', 'DEAD_LETTER'))
        OR (OLD.state = 'IN_FLIGHT' AND NEW.state IN ('PUBLISHED', 'RETRY_WAIT', 'DEAD_LETTER'))) THEN
        RAISE EXCEPTION 'illegal command_outbox transition % -> % for command %', OLD.state, NEW.state, OLD.command_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.state = 'IN_FLIGHT' AND OLD.state <> 'IN_FLIGHT' THEN
        IF NEW.attempt_count <> OLD.attempt_count + 1 THEN
            RAISE EXCEPTION 'claiming command % must increment attempt_count by exactly one', OLD.command_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF clock_timestamp() >= OLD.expires_at THEN
            RAISE EXCEPTION 'command % has expired and cannot be claimed', OLD.command_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF NEW.attempt_count <> OLD.attempt_count THEN
        RAISE EXCEPTION 'attempt_count of command % may only change when the row is claimed', OLD.command_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
