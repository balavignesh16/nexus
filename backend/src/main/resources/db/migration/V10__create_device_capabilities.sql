-- V10__create_device_capabilities.sql
--
-- Tier 5C: NEXUS-authoritative device capability metadata - deliberately
-- separate from devices.status (lifecycle), device_connectivity (Tier 5B
-- physical MQTT connectivity), digital_twins (telemetry-observed state),
-- and telemetry's own sensorType wire vocabulary. A capability row is a
-- durable fact about what a device is provisioned to measure or control;
-- it is never created or modified by telemetry ingestion.

CREATE TABLE device_capabilities (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    capability_type VARCHAR(50) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    unit VARCHAR(50),
    metadata JSONB,
    CONSTRAINT uq_device_capability UNIQUE (device_id, capability_type, direction)
);

CREATE INDEX idx_device_capabilities_device_id ON device_capabilities (device_id);
