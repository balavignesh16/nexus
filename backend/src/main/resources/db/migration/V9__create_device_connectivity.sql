-- V9__create_device_connectivity.sql
--
-- Tier 5B: physical MQTT connectivity (ONLINE/OFFLINE), deliberately
-- separate from both `devices.status` (REGISTERED/ACTIVE/OFFLINE/
-- MAINTENANCE - a manually-managed lifecycle field, untouched by this
-- migration) and `digital_twins` (telemetry-observed sensor state). A
-- device with no row here is UNKNOWN - never observed - a state
-- deliberately not persisted, only represented by absence.

CREATE TABLE device_connectivity (
    device_id UUID PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    last_changed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
