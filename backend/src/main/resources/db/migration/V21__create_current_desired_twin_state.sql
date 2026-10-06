-- P2.1: current command intent, separate from device-reported digital_twins.
-- action_history.request_id is the primary key (V6), so it is a valid FK target.
CREATE TABLE current_desired_twin_state (
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    target_key VARCHAR(100) NOT NULL,
    binding_id UUID REFERENCES device_capability_bindings(id) ON DELETE RESTRICT,
    legacy_capability_type VARCHAR(50),
    subtarget_key VARCHAR(100) NOT NULL DEFAULT '',
    capability_definition_id UUID REFERENCES capability_definitions(id) ON DELETE RESTRICT,
    capability_key VARCHAR(100) NOT NULL,
    value_type VARCHAR(20) NOT NULL,
    numeric_value NUMERIC,
    boolean_value BOOLEAN,
    string_value TEXT,
    object_value JSONB,
    unit VARCHAR(50),
    source VARCHAR(30) NOT NULL,
    command_id UUID NOT NULL REFERENCES action_history(request_id) ON DELETE RESTRICT,
    requested_at TIMESTAMPTZ NOT NULL,
    accepted_for_dispatch_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_current_desired_twin_state PRIMARY KEY (device_id, target_key, subtarget_key),
    CONSTRAINT ck_desired_identity_xor CHECK ((binding_id IS NOT NULL) <> (legacy_capability_type IS NOT NULL)),
    CONSTRAINT ck_desired_target_key CHECK (
        (binding_id IS NOT NULL AND target_key = 'binding:' || binding_id::text)
        OR (legacy_capability_type IS NOT NULL AND target_key = 'legacy:' || legacy_capability_type)
    ),
    CONSTRAINT ck_desired_subtarget CHECK (subtarget_key = '' OR subtarget_key ~ '^channel:[1-9][0-9]*$'),
    CONSTRAINT ck_desired_value_type CHECK (value_type IN ('NUMBER','BOOLEAN','STRING','ENUM','OBJECT')),
    CONSTRAINT ck_desired_typed_value CHECK (
        (value_type = 'NUMBER' AND numeric_value IS NOT NULL AND boolean_value IS NULL AND string_value IS NULL AND object_value IS NULL)
        OR (value_type = 'BOOLEAN' AND boolean_value IS NOT NULL AND numeric_value IS NULL AND string_value IS NULL AND object_value IS NULL)
        OR (value_type IN ('STRING','ENUM') AND string_value IS NOT NULL AND numeric_value IS NULL AND boolean_value IS NULL AND object_value IS NULL)
        OR (value_type = 'OBJECT' AND object_value IS NOT NULL AND numeric_value IS NULL AND boolean_value IS NULL AND string_value IS NULL)
    )
);
CREATE INDEX idx_current_desired_command ON current_desired_twin_state(command_id);
