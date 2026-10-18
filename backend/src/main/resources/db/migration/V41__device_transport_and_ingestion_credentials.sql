-- V41__device_transport_and_ingestion_credentials.sql
--
-- Phase 5 P5.V41 (adapter-contract.md section 12). Forward-only: V1 to V40 are not modified. Nothing in V37 to V40 is
-- touched either: command_outbox.topic keeps its LOGICAL address 'nexus/devices/<id>/commands' for every transport (the V37
-- CHECK binds that string, not the protocol), which spike S1 verified against a disposable database before this migration was
-- committed (HttpTransportSchemaCompatibilityTest).
--
-- 1. D3, server-controlled transport assignment. Existing rows read MQTT_JSON through a constant default, which PostgreSQL
--    stores in the catalogue without rewriting the table, so no behaviour changes for any existing device.
-- 2. D2, per-device ingestion credentials: a public lookup id and a one-way hash of the secret only. The plaintext key is
--    shown once by the administration CLI and is never stored. There is deliberately NO network surface that writes this
--    table (D7); the backend role only reads it and updates last_valid_contact_at.
-- 3. D9, last_valid_contact_at: the database-clock instant of the last VALID authenticated contact, an eligibility signal
--    for command acceptance. It is not connectivity and nothing here writes the connectivity registry.
-- 4. An append-only record of credential lifecycle events (no secret material).

ALTER TABLE devices ADD COLUMN transport VARCHAR(24) NOT NULL DEFAULT 'MQTT_JSON';
ALTER TABLE devices ADD CONSTRAINT ck_devices_transport CHECK (transport IN ('MQTT_JSON', 'HTTP_WEBHOOK', 'SIMULATOR'));

ALTER TABLE device_templates ADD COLUMN default_transport VARCHAR(24) NOT NULL DEFAULT 'MQTT_JSON';
ALTER TABLE device_templates ADD CONSTRAINT ck_templates_transport CHECK (default_transport IN ('MQTT_JSON', 'HTTP_WEBHOOK', 'SIMULATOR'));

CREATE TABLE device_ingestion_credentials (
    credential_id         UUID PRIMARY KEY,
    device_id             UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    lookup_id             VARCHAR(24) NOT NULL UNIQUE,
    secret_hash           BYTEA NOT NULL,
    hash_algorithm        VARCHAR(24) NOT NULL,
    status                VARCHAR(12) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL,
    retire_at             TIMESTAMPTZ,
    revoked_at            TIMESTAMPTZ,
    last_valid_contact_at TIMESTAMPTZ,
    CONSTRAINT ck_credential_status CHECK (status IN ('ACTIVE', 'RETIRING', 'REVOKED')),
    CONSTRAINT ck_credential_hash_len CHECK (octet_length(secret_hash) = 32),
    CONSTRAINT ck_credential_lookup_format CHECK (lookup_id ~ '^[A-Za-z0-9]{8,24}$'),
    CONSTRAINT ck_credential_algorithm CHECK (hash_algorithm ~ '^[A-Z0-9_-]{1,24}$'),
    CONSTRAINT ck_credential_retire CHECK (status <> 'RETIRING' OR retire_at IS NOT NULL),
    CONSTRAINT ck_credential_revoke CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

-- At most one ACTIVE and one RETIRING credential per device, so at most two non-revoked credentials in total.
CREATE UNIQUE INDEX uq_one_active_credential ON device_ingestion_credentials (device_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_one_retiring_credential ON device_ingestion_credentials (device_id) WHERE status = 'RETIRING';
CREATE INDEX idx_credentials_device ON device_ingestion_credentials (device_id) WHERE status <> 'REVOKED';

CREATE TABLE device_credential_events (
    event_id      UUID PRIMARY KEY,
    credential_id UUID NOT NULL REFERENCES device_ingestion_credentials(credential_id) ON DELETE CASCADE,
    event_type    VARCHAR(16) NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,
    actor         VARCHAR(80) NOT NULL,
    CONSTRAINT ck_credential_event_type CHECK (event_type IN ('CREATED', 'ROTATED_IN', 'RETIRING', 'REVOKED', 'EXPIRED'))
);

CREATE INDEX idx_credential_events_credential ON device_credential_events (credential_id, occurred_at);

-- Append-only, the pattern of command_state_history (V39): an UPDATE is refused; a DELETE is allowed only once the parent
-- credential row is gone (the ON DELETE CASCADE of a deleted credential or device).
CREATE FUNCTION device_credential_events_guard_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'device_credential_events is append-only (event %)', OLD.event_id
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_device_credential_events_no_update BEFORE UPDATE ON device_credential_events
    FOR EACH ROW EXECUTE FUNCTION device_credential_events_guard_update();

CREATE FUNCTION device_credential_events_guard_delete() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM device_ingestion_credentials WHERE credential_id = OLD.credential_id) THEN
        RAISE EXCEPTION 'device_credential_events is append-only; rows leave only with their credential (event %)', OLD.event_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_device_credential_events_guard_delete BEFORE DELETE ON device_credential_events
    FOR EACH ROW EXECUTE FUNCTION device_credential_events_guard_delete();
