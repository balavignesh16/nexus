-- V40__inbound_message_dedupe.sql
--
-- Phase 4 P4.5: telemetry inbox deduplication (contract R17). Forward-only: V1 to V39 are not modified, and
-- telemetry_history (a TimescaleDB hypertable whose unique constraints would have to include the partition column)
-- is NOT altered. This migration creates one table and one index and changes no existing row.
--
-- Table design:
--   * (device_id, message_id) is the primary key. The key space is per device and is shared by the MQTT and HTTP
--     entry points: "source" is a diagnostic label only and is deliberately NOT part of any key or constraint, so an
--     id first seen over MQTT is a duplicate when it later arrives over HTTP, and the other way round.
--   * "fingerprint" is the lowercase hexadecimal SHA-256 of the canonical telemetry-processing inputs (see
--     TelemetryFingerprint). It lets a second arrival of the same id be classified as an identical duplicate (same
--     fingerprint) or a conflicting duplicate (different fingerprint).
--   * "telemetry_record_id" is the id of the telemetry row written in the same transaction. It is nullable and has NO
--     foreign key: telemetry_history is a hypertable, and the telemetry row may be dropped by retention before this
--     row is purged. It is a reference that can become stale, not a guarantee.
--   * "received_at" defaults to the database clock; retention purges by it. The application never supplies it.
--   * device_id references devices ON DELETE CASCADE. This holds no delivery obligation or delivery evidence (contrast
--     command_outbox, R30): it is a replay-protection cache, so removing a device removes its entries and can neither
--     block the deletion nor leave an orphan. The V38 device-deletion guard concerns command_outbox only.

CREATE TABLE inbound_message_dedupe (
    device_id           UUID         NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    message_id          VARCHAR(64)  NOT NULL,
    source              VARCHAR(8)   NOT NULL,
    fingerprint         CHAR(64)     NOT NULL,
    telemetry_record_id UUID,
    received_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_inbound_message_dedupe PRIMARY KEY (device_id, message_id),
    CONSTRAINT ck_imd_message_id CHECK (message_id ~ '^[A-Za-z0-9._:-]{1,64}$'),
    CONSTRAINT ck_imd_source CHECK (source IN ('MQTT', 'HTTP')),
    CONSTRAINT ck_imd_fingerprint CHECK (fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_inbound_message_dedupe_received ON inbound_message_dedupe (received_at);
