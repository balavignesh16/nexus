CREATE TABLE capability_definitions (
    id UUID PRIMARY KEY,
    capability_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(150) NOT NULL,
    description TEXT,
    direction VARCHAR(20) NOT NULL CHECK (direction IN ('READ', 'WRITE')),
    value_type VARCHAR(20) NOT NULL CHECK (value_type IN ('NUMBER', 'BOOLEAN', 'STRING', 'ENUM', 'OBJECT')),
    unit VARCHAR(50),
    allowed_units JSONB,
    minimum_value NUMERIC,
    maximum_value NUMERIC,
    enum_values JSONB,
    supported_commands JSONB,
    semantic_category VARCHAR(100),
    object_constraints JSONB,
    metadata JSONB,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_capability_definitions_key UNIQUE (capability_key),
    CONSTRAINT ck_capability_definitions_key CHECK (capability_key ~ '^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$'),
    CONSTRAINT ck_capability_definitions_bounds CHECK (
        minimum_value IS NULL OR maximum_value IS NULL OR minimum_value <= maximum_value
    )
);

INSERT INTO capability_definitions
    (id, capability_key, display_name, direction, value_type, unit, allowed_units,
     minimum_value, maximum_value, enum_values, supported_commands,
     semantic_category, object_constraints, metadata)
VALUES
    ('10000000-0000-0000-0000-000000000001', 'temperature', 'Temperature', 'READ', 'NUMBER', 'CELSIUS', '["CELSIUS"]', NULL, NULL, NULL, '[]', 'environmental', NULL, '{}'),
    ('10000000-0000-0000-0000-000000000002', 'humidity', 'Humidity', 'READ', 'NUMBER', 'PERCENT', '["PERCENT"]', NULL, NULL, NULL, '[]', 'environmental', NULL, '{}'),
    ('10000000-0000-0000-0000-000000000003', 'motion', 'Motion', 'READ', 'BOOLEAN', 'BOOLEAN', '["BOOLEAN"]', NULL, NULL, NULL, '[]', 'occupancy', NULL, '{}'),
    ('10000000-0000-0000-0000-000000000004', 'led', 'LED', 'WRITE', 'BOOLEAN', 'BOOLEAN', '["BOOLEAN"]', NULL, NULL, NULL, '["SET_STATE"]', 'actuator', NULL, '{}'),
    ('10000000-0000-0000-0000-000000000005', 'pressure', 'Pressure', 'READ', 'NUMBER', 'HPA', '["HPA"]', NULL, NULL, NULL, '[]', 'environmental', NULL, '{}'),
    ('10000000-0000-0000-0000-000000000006', 'gas', 'Gas', 'READ', 'NUMBER', 'PPM', '["PPM"]', NULL, NULL, NULL, '[]', 'safety', NULL, '{}'),
    ('10000000-0000-0000-0000-000000000007', 'button', 'Button', 'READ', 'BOOLEAN', 'BOOLEAN', '["BOOLEAN"]', NULL, NULL, NULL, '[]', 'input', NULL, '{}'),
    ('10000000-0000-0000-0000-000000000008', 'relay', 'Relay', 'WRITE', 'OBJECT', 'BOOLEAN', '["BOOLEAN"]', NULL, NULL, NULL, '["SET_STATE"]', 'actuator', '{"required":["channel","state"],"channelMinimum":1}', '{}'),
    ('10000000-0000-0000-0000-000000000009', 'servo', 'Servo', 'WRITE', 'NUMBER', 'DEGREES', '["DEGREES"]', 0, 180, NULL, '["SET_STATE"]', 'actuator', NULL, '{}'),
    ('10000000-0000-0000-0000-000000000010', 'fan', 'Fan', 'WRITE', 'BOOLEAN', 'BOOLEAN', '["BOOLEAN"]', NULL, NULL, NULL, '["SET_STATE"]', 'actuator', NULL, '{}'),
    ('10000000-0000-0000-0000-000000000011', 'buzzer', 'Buzzer', 'WRITE', 'BOOLEAN', 'BOOLEAN', '["BOOLEAN"]', NULL, NULL, NULL, '["SET_STATE"]', 'actuator', NULL, '{}');
