CREATE TABLE device_onboarding_sessions (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL UNIQUE REFERENCES devices(id) ON DELETE RESTRICT,
    template_id UUID NOT NULL REFERENCES device_templates(id) ON DELETE RESTRICT,
    request_key VARCHAR(150) NOT NULL UNIQUE,
    request_hash VARCHAR(64) NOT NULL,
    state VARCHAR(40) NOT NULL,
    contract_version VARCHAR(20) NOT NULL,
    status_deadline TIMESTAMPTZ,
    telemetry_deadline TIMESTAMPTZ,
    first_status_at TIMESTAMPTZ,
    first_telemetry_at TIMESTAMPTZ,
    failure_code VARCHAR(100),
    failure_message TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_onboarding_state CHECK (state IN (
        'WAITING_FOR_STATUS', 'WAITING_FOR_TELEMETRY', 'OPERATIONAL',
        'STATUS_TIMEOUT', 'TELEMETRY_REJECTED', 'DISABLED'))
);

CREATE INDEX idx_onboarding_state_status_deadline
    ON device_onboarding_sessions(state, status_deadline);

-- Generic typed capabilities may be unitless; legacy telemetry still supplies unit.
ALTER TABLE telemetry_history ALTER COLUMN unit DROP NOT NULL;
