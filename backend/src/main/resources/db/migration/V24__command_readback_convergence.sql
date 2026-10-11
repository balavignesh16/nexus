-- P2.4: explicit readback policy, current verification, and durable evidence.
CREATE TABLE template_readback_mappings (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES device_templates(id) ON DELETE CASCADE,
    write_template_capability_id UUID NOT NULL REFERENCES device_template_capabilities(id) ON DELETE CASCADE,
    write_subtarget_key VARCHAR(100) NOT NULL DEFAULT '',
    read_template_capability_id UUID NOT NULL REFERENCES device_template_capabilities(id) ON DELETE CASCADE,
    selector_key VARCHAR(50) NOT NULL,
    selector_path VARCHAR(200),
    comparator_key VARCHAR(50) NOT NULL,
    numeric_tolerance NUMERIC,
    qualification_mode VARCHAR(30) NOT NULL,
    verification_timeout_seconds INTEGER NOT NULL,
    CONSTRAINT uq_template_readback_target UNIQUE (template_id, write_template_capability_id, write_subtarget_key),
    CONSTRAINT ck_template_readback_subtarget CHECK (write_subtarget_key = '' OR write_subtarget_key ~ '^channel:[1-9][0-9]*$'),
    CONSTRAINT ck_template_readback_timeout CHECK (verification_timeout_seconds > 0),
    CONSTRAINT ck_template_readback_tolerance CHECK (numeric_tolerance IS NULL OR numeric_tolerance >= 0),
    CONSTRAINT ck_template_readback_qualification CHECK (qualification_mode IN ('NONE','COMMAND_ECHO'))
);

CREATE TABLE device_readback_mappings (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    write_binding_id UUID NOT NULL REFERENCES device_capability_bindings(id) ON DELETE CASCADE,
    write_subtarget_key VARCHAR(100) NOT NULL DEFAULT '',
    read_binding_id UUID NOT NULL REFERENCES device_capability_bindings(id) ON DELETE CASCADE,
    selector_key VARCHAR(50) NOT NULL,
    selector_path VARCHAR(200),
    comparator_key VARCHAR(50) NOT NULL,
    numeric_tolerance NUMERIC,
    qualification_mode VARCHAR(30) NOT NULL,
    verification_timeout_seconds INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    template_mapping_id UUID REFERENCES template_readback_mappings(id) ON DELETE SET NULL,
    CONSTRAINT ck_device_readback_distinct CHECK (write_binding_id <> read_binding_id),
    CONSTRAINT ck_device_readback_subtarget CHECK (write_subtarget_key = '' OR write_subtarget_key ~ '^channel:[1-9][0-9]*$'),
    CONSTRAINT ck_device_readback_timeout CHECK (verification_timeout_seconds > 0),
    CONSTRAINT ck_device_readback_tolerance CHECK (numeric_tolerance IS NULL OR numeric_tolerance >= 0),
    CONSTRAINT ck_device_readback_qualification CHECK (qualification_mode IN ('NONE','COMMAND_ECHO'))
);
CREATE UNIQUE INDEX uq_enabled_device_readback_target
    ON device_readback_mappings(device_id, write_binding_id, write_subtarget_key) WHERE enabled;
CREATE INDEX idx_device_readback_source ON device_readback_mappings(device_id, read_binding_id) WHERE enabled;

ALTER TABLE action_history ADD COLUMN published_at TIMESTAMPTZ;
ALTER TABLE action_history ADD COLUMN ack_backend_received_at TIMESTAMPTZ;
ALTER TABLE action_history ADD COLUMN ack_device_timestamp TIMESTAMPTZ;
ALTER TABLE action_history ADD COLUMN ack_deadline_at TIMESTAMPTZ;

CREATE TABLE current_twin_verification_state (
    device_id UUID NOT NULL,
    target_key VARCHAR(100) NOT NULL,
    subtarget_key VARCHAR(100) NOT NULL DEFAULT '',
    desired_command_id UUID NOT NULL,
    verification_status VARCHAR(20) NOT NULL,
    verification_updated_at TIMESTAMPTZ NOT NULL,
    verification_deadline_at TIMESTAMPTZ,
    mapping_id UUID,
    last_readback_telemetry_id UUID,
    last_readback_state_key VARCHAR(100),
    last_readback_binding_id UUID,
    last_readback_event_time TIMESTAMPTZ,
    last_readback_received_at TIMESTAMPTZ,
    last_readback_correlation_id VARCHAR(255),
    readback_evidence VARCHAR(50),
    readback_attribution VARCHAR(50),
    comparator_key VARCHAR(50),
    comparison_result VARCHAR(30),
    numeric_tolerance NUMERIC,
    readback_value JSONB,
    discrepancy_reason VARCHAR(100),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, target_key, subtarget_key),
    FOREIGN KEY (device_id, target_key, subtarget_key)
        REFERENCES current_desired_twin_state(device_id, target_key, subtarget_key) ON DELETE CASCADE,
    CONSTRAINT ck_current_verification_status CHECK (verification_status IN ('UNVERIFIED','PENDING','CONVERGED','DIVERGED','TIMED_OUT'))
);
CREATE INDEX idx_verification_pending_deadline ON current_twin_verification_state(verification_deadline_at)
    WHERE verification_status = 'PENDING';
INSERT INTO current_twin_verification_state
    (device_id, target_key, subtarget_key, desired_command_id, verification_status,
     verification_updated_at, discrepancy_reason)
SELECT device_id, target_key, subtarget_key, command_id, 'UNVERIFIED',
       accepted_for_dispatch_at, 'MIGRATED_WITHOUT_VERIFICATION_BASELINE'
FROM current_desired_twin_state;

ALTER TABLE twin_state_transitions DROP CONSTRAINT ck_twin_transition_type;
ALTER TABLE twin_state_transitions ADD CONSTRAINT ck_twin_transition_type
    CHECK (transition_type IN ('REPORTED_STATE_CHANGED','DESIRED_STATE_CHANGED','DESIRED_STATE_CLEARED','CONVERGENCE_CHANGED'));
ALTER TABLE twin_state_transitions ADD COLUMN previous_verification_status VARCHAR(20);
ALTER TABLE twin_state_transitions ADD COLUMN new_verification_status VARCHAR(20);
ALTER TABLE twin_state_transitions ADD COLUMN verification_command_id UUID;
ALTER TABLE twin_state_transitions ADD COLUMN verification_mapping_id UUID;
ALTER TABLE twin_state_transitions ADD COLUMN readback_telemetry_id UUID;
ALTER TABLE twin_state_transitions ADD COLUMN readback_state_key VARCHAR(100);
ALTER TABLE twin_state_transitions ADD COLUMN readback_binding_id UUID;
ALTER TABLE twin_state_transitions ADD COLUMN readback_event_time TIMESTAMPTZ;
ALTER TABLE twin_state_transitions ADD COLUMN readback_received_at TIMESTAMPTZ;
ALTER TABLE twin_state_transitions ADD COLUMN readback_evidence VARCHAR(50);
ALTER TABLE twin_state_transitions ADD COLUMN readback_attribution VARCHAR(50);
ALTER TABLE twin_state_transitions ADD COLUMN comparator_key VARCHAR(50);
ALTER TABLE twin_state_transitions ADD COLUMN comparison_result VARCHAR(30);
ALTER TABLE twin_state_transitions ADD COLUMN numeric_tolerance NUMERIC;
ALTER TABLE twin_state_transitions ADD COLUMN verification_reason VARCHAR(100);
