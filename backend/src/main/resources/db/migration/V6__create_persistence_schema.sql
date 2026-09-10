-- V6__create_persistence_schema.sql

-- Required for create_hypertable() / drop_chunks() below; not enabled by default
-- even on the timescale/timescaledb image.
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Digital Twin Current State
CREATE TABLE digital_twins (
    device_id UUID PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    latest_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    latest_sensor_type VARCHAR(255),
    latest_value DOUBLE PRECISION,
    latest_unit VARCHAR(50),
    last_seen TIMESTAMP WITH TIME ZONE NOT NULL,
    metadata JSONB DEFAULT '{}'::jsonb
);

-- Telemetry (Hypertable)
CREATE TABLE telemetry_history (
    id UUID NOT NULL,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    sensor_type VARCHAR(100) NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    unit VARCHAR(50) NOT NULL,
    PRIMARY KEY (id, timestamp)
);
SELECT create_hypertable('telemetry_history', 'timestamp');
CREATE INDEX idx_telemetry_device_time ON telemetry_history (device_id, timestamp DESC);

-- Domain Events
CREATE TABLE domain_events (
    event_id UUID PRIMARY KEY,
    device_id UUID REFERENCES devices(id) ON DELETE SET NULL,
    event_type VARCHAR(100) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    source VARCHAR(255),
    severity VARCHAR(50) NOT NULL,
    payload JSONB DEFAULT '{}'::jsonb,
    correlation_id VARCHAR(255)
);
CREATE INDEX idx_domain_events_device_time ON domain_events (device_id, timestamp DESC);
CREATE INDEX idx_domain_events_severity ON domain_events (severity);

-- Action History
CREATE TABLE action_history (
    request_id UUID PRIMARY KEY,
    match_id UUID NOT NULL,
    device_id UUID REFERENCES devices(id) ON DELETE SET NULL,
    action_type VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL,
    parameters JSONB,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    message TEXT
);
CREATE INDEX idx_action_history_device_time ON action_history (device_id, timestamp DESC);

-- Pipeline Traces (Hypertable)
CREATE TABLE pipeline_traces (
    trace_id VARCHAR(255) NOT NULL,
    start_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    end_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    correlation_id VARCHAR(255) NOT NULL,
    device_id VARCHAR(255),
    stage VARCHAR(100) NOT NULL,
    duration_ms BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,
    metadata JSONB,
    PRIMARY KEY (trace_id, start_timestamp)
);
SELECT create_hypertable('pipeline_traces', 'start_timestamp');
CREATE INDEX idx_pipeline_corr_id ON pipeline_traces (correlation_id);
