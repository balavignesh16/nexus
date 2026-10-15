-- V37__create_command_outbox.sql
--
-- Phase 4 P4.1: durable command delivery obligation (command_outbox).
-- Forward-only. Nothing in this migration backfills, replays or republishes any existing command:
-- the table starts EMPTY and commands accepted before V37 have no outbox row (contract R16, decision D3).
-- Nothing reads this table yet; the outbox feature flag defaults OFF and production dispatch is unchanged.
--
-- Referencing and deletion policy (reviewed against the existing schema before this file was written):
--   * action_history.request_id is the primary key (V6). It is already referenced by
--     current_desired_twin_state.command_id ON DELETE RESTRICT (V21).
--   * command_outbox.command_id -> action_history(request_id) ON DELETE RESTRICT. A parent row can never
--     silently take its delivery obligation with it. The retention job purges TERMINAL outbox rows first
--     and then deletes only action_history rows that have no outbox row (RetentionManager).
--   * A BEFORE DELETE trigger refuses to delete a QUEUED, IN_FLIGHT or RETRY_WAIT row while its device still
--     exists, so no retention or manual DELETE can destroy a pending or unresolved obligation.
--   * command_outbox.device_id -> devices(id) ON DELETE CASCADE, like current_desired_twin_state (V21). Removing
--     a device removes the obligations addressed to it; the delete guard allows that cascade because the device
--     row is already gone when the cascade runs.

CREATE TABLE command_outbox (
    command_id                 UUID PRIMARY KEY REFERENCES action_history(request_id) ON DELETE RESTRICT,
    device_id                  UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    topic                      VARCHAR(255) NOT NULL,
    payload_text               TEXT NOT NULL,
    payload_sha256             VARCHAR(64) NOT NULL,
    payload_epoch_seconds      BIGINT NOT NULL,
    state                      VARCHAR(16) NOT NULL,
    attempt_count              INTEGER NOT NULL DEFAULT 0,
    max_attempts               INTEGER NOT NULL,
    next_attempt_at            TIMESTAMPTZ,
    lease_owner                VARCHAR(128),
    lease_expires_at           TIMESTAMPTZ,
    expires_at                 TIMESTAMPTZ NOT NULL,
    first_publish_confirmed_at TIMESTAMPTZ,
    terminal_at                TIMESTAMPTZ,
    terminal_reason            VARCHAR(32),
    last_error_code            VARCHAR(64),
    correlation_id             VARCHAR(255),
    created_at                 TIMESTAMPTZ NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,

    -- Identity and immutable payload integrity. The payload text is the exact JSON published on every attempt;
    -- its identity fields must agree with the columns, and its SHA-256 must be the hash of those exact bytes.
    CONSTRAINT ck_outbox_topic CHECK (topic = 'nexus/devices/' || device_id::text || '/commands'),
    CONSTRAINT ck_outbox_payload_size CHECK (char_length(payload_text) BETWEEN 2 AND 4096),
    CONSTRAINT ck_outbox_payload_object CHECK (jsonb_typeof(payload_text::jsonb) = 'object'),
    CONSTRAINT ck_outbox_payload_command_id CHECK ((payload_text::jsonb ->> 'commandId') IS NOT DISTINCT FROM command_id::text),
    CONSTRAINT ck_outbox_payload_timestamp CHECK ((payload_text::jsonb ->> 'timestamp')::bigint IS NOT DISTINCT FROM payload_epoch_seconds),
    CONSTRAINT ck_outbox_payload_hash_format CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_outbox_payload_hash CHECK (payload_sha256 = encode(sha256(convert_to(payload_text, 'UTF8')), 'hex')),

    -- State model (contract R05).
    CONSTRAINT ck_outbox_state CHECK (state IN ('QUEUED', 'IN_FLIGHT', 'RETRY_WAIT', 'PUBLISHED', 'DEAD_LETTER')),
    CONSTRAINT ck_outbox_terminal_reason CHECK (terminal_reason IS NULL OR terminal_reason IN
        ('RETRY_EXHAUSTED', 'EXPIRED', 'PERMANENT_FAILURE', 'PAYLOAD_INTEGRITY', 'UNCERTAIN_EXPIRED')),

    -- Bounded attempts. The ceiling of 5 is a hard database bound; the configured maximum is validated at startup.
    CONSTRAINT ck_outbox_max_attempts CHECK (max_attempts BETWEEN 1 AND 5),
    CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count >= 0 AND attempt_count <= max_attempts),

    -- Bounded expiry. The delivery window may never exceed 20 seconds (30 second firmware freshness check
    -- minus a 10 second clock and network uncertainty margin; contract R11) and must be positive.
    CONSTRAINT ck_outbox_expiry CHECK (
        expires_at > to_timestamp(payload_epoch_seconds)
        AND expires_at <= to_timestamp(payload_epoch_seconds) + INTERVAL '20 seconds'),

    -- Lease metadata exists if and only if the row is IN_FLIGHT.
    CONSTRAINT ck_outbox_lease CHECK (
        (state = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (state <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_expires_at IS NULL)),

    -- A retry time exists only while the row waits to be claimed.
    CONSTRAINT ck_outbox_next_attempt CHECK (
        (state IN ('QUEUED', 'RETRY_WAIT') AND next_attempt_at IS NOT NULL)
        OR (state NOT IN ('QUEUED', 'RETRY_WAIT') AND next_attempt_at IS NULL)),

    -- Attempt counts follow the state: never attempted while QUEUED, at least one attempt otherwise
    -- (a DEAD_LETTER row may have none: it can expire or fail permanently before any attempt).
    CONSTRAINT ck_outbox_attempt_state CHECK (
        (state = 'QUEUED' AND attempt_count = 0)
        OR (state IN ('IN_FLIGHT', 'RETRY_WAIT', 'PUBLISHED') AND attempt_count >= 1)
        OR state = 'DEAD_LETTER'),

    -- Terminal coherence: PUBLISHED and DEAD_LETTER are terminal for delivery; a dead letter always records why.
    CONSTRAINT ck_outbox_terminal CHECK (
        (state = 'PUBLISHED' AND terminal_at IS NOT NULL AND terminal_reason IS NULL AND first_publish_confirmed_at IS NOT NULL)
        OR (state = 'DEAD_LETTER' AND terminal_at IS NOT NULL AND terminal_reason IS NOT NULL AND first_publish_confirmed_at IS NULL)
        OR (state IN ('QUEUED', 'IN_FLIGHT', 'RETRY_WAIT') AND terminal_at IS NULL AND terminal_reason IS NULL AND first_publish_confirmed_at IS NULL))
);

-- Claim order for the dispatcher (P4.2): eligible rows by time, oldest first.
CREATE INDEX idx_command_outbox_claim ON command_outbox (next_attempt_at, created_at)
    WHERE state IN ('QUEUED', 'RETRY_WAIT');
-- Per-device lookups over unresolved obligations (ordering and queue caps in later slices).
CREATE INDEX idx_command_outbox_device_unresolved ON command_outbox (device_id, created_at)
    WHERE state IN ('QUEUED', 'RETRY_WAIT', 'IN_FLIGHT');
-- Lease-expiry and delivery-expiry sweeps (P4.2, P4.3).
CREATE INDEX idx_command_outbox_lease_expiry ON command_outbox (lease_expires_at) WHERE state = 'IN_FLIGHT';
CREATE INDEX idx_command_outbox_expiry ON command_outbox (expires_at) WHERE state IN ('QUEUED', 'RETRY_WAIT');
-- Retention purge of terminal rows.
CREATE INDEX idx_command_outbox_terminal ON command_outbox (terminal_at) WHERE state IN ('PUBLISHED', 'DEAD_LETTER');
-- Database-level guarantee of at most one IN_FLIGHT command per device. This is a single-in-flight guard only;
-- it is NOT a claim of strict per-device FIFO ordering, which belongs to the dispatcher and must be proven in P4.2.
CREATE UNIQUE INDEX uq_command_outbox_device_in_flight ON command_outbox (device_id) WHERE state = 'IN_FLIGHT';

-- Identity, payload and delivery bounds are immutable; terminal rows are immutable; only the transitions of
-- contract R05 are legal; attempt_count never decreases and increases by exactly one when a row is claimed.
CREATE FUNCTION command_outbox_guard_update() RETURNS trigger AS $$
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
    ELSIF NEW.attempt_count <> OLD.attempt_count THEN
        RAISE EXCEPTION 'attempt_count of command % may only change when the row is claimed', OLD.command_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_command_outbox_guard_update BEFORE UPDATE ON command_outbox
    FOR EACH ROW EXECUTE FUNCTION command_outbox_guard_update();

-- A pending or unresolved obligation cannot be deleted while its device still exists. When the device itself is
-- being removed the cascade runs after the device row is gone and is allowed.
CREATE FUNCTION command_outbox_guard_delete() RETURNS trigger AS $$
BEGIN
    IF OLD.state IN ('QUEUED', 'IN_FLIGHT', 'RETRY_WAIT')
       AND EXISTS (SELECT 1 FROM devices WHERE id = OLD.device_id) THEN
        RAISE EXCEPTION 'command_outbox row % is % and cannot be deleted while its device exists', OLD.command_id, OLD.state
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_command_outbox_guard_delete BEFORE DELETE ON command_outbox
    FOR EACH ROW EXECUTE FUNCTION command_outbox_guard_delete();
