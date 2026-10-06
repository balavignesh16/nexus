-- P2.2: canonical reported-state identity plus append-only Twin history.
ALTER TABLE digital_twins ADD COLUMN state_key VARCHAR(100);
ALTER TABLE digital_twins ADD COLUMN legacy_capability_type VARCHAR(100);
ALTER TABLE digital_twins ADD COLUMN last_wire_identifier VARCHAR(255);

UPDATE digital_twins
SET state_key = CASE
        WHEN binding_id IS NOT NULL THEN 'binding:' || binding_id::text
        ELSE 'legacy:' || CASE
            WHEN UPPER(latest_sensor_type) LIKE '%\_SENSOR' ESCAPE '\' THEN LEFT(UPPER(latest_sensor_type), -7)
            ELSE UPPER(latest_sensor_type)
        END
    END,
    legacy_capability_type = CASE
        WHEN binding_id IS NULL THEN CASE
            WHEN UPPER(latest_sensor_type) LIKE '%\_SENSOR' ESCAPE '\' THEN LEFT(UPPER(latest_sensor_type), -7)
            ELSE UPPER(latest_sensor_type)
        END
        ELSE NULL
    END,
    last_wire_identifier = latest_sensor_type;

CREATE TABLE digital_twin_alias_reconciliation_audit (
    audit_id UUID PRIMARY KEY,
    device_id UUID NOT NULL,
    state_key VARCHAR(100) NOT NULL,
    authoritative_external_identifier VARCHAR(255) NOT NULL,
    losing_external_identifier VARCHAR(255) NOT NULL,
    reason VARCHAR(100) NOT NULL,
    reconciled_at TIMESTAMPTZ NOT NULL,
    original_row JSONB NOT NULL
);

WITH ranked AS (
    SELECT d.*, ROW_NUMBER() OVER (
        PARTITION BY device_id, state_key
        ORDER BY latest_timestamp DESC, last_seen DESC, latest_sensor_type ASC
    ) AS rank,
    FIRST_VALUE(latest_sensor_type) OVER (
        PARTITION BY device_id, state_key
        ORDER BY latest_timestamp DESC, last_seen DESC, latest_sensor_type ASC
    ) AS winner
    FROM digital_twins d
), losers AS (
    SELECT * FROM ranked WHERE rank > 1
)
INSERT INTO digital_twin_alias_reconciliation_audit
    (audit_id, device_id, state_key, authoritative_external_identifier,
     losing_external_identifier, reason, reconciled_at, original_row)
SELECT gen_random_uuid(), device_id, state_key, winner, latest_sensor_type,
       'DUPLICATE_ALIAS_STATE', NOW(), to_jsonb(losers) - 'rank' - 'winner'
FROM losers;

WITH ranked AS (
    SELECT device_id, latest_sensor_type, ROW_NUMBER() OVER (
        PARTITION BY device_id, state_key
        ORDER BY latest_timestamp DESC, last_seen DESC, latest_sensor_type ASC
    ) AS rank
    FROM digital_twins
)
DELETE FROM digital_twins d USING ranked r
WHERE d.device_id = r.device_id AND d.latest_sensor_type = r.latest_sensor_type AND r.rank > 1;

UPDATE digital_twins d
SET latest_sensor_type = COALESCE(
    NULLIF(b.external_identifiers ->> 'telemetryKey', ''),
    d.capability_key,
    d.latest_sensor_type)
FROM device_capability_bindings b
WHERE d.binding_id = b.id;

ALTER TABLE digital_twins DROP CONSTRAINT digital_twins_pkey;
ALTER TABLE digital_twins ALTER COLUMN state_key SET NOT NULL;
ALTER TABLE digital_twins ADD CONSTRAINT digital_twins_pkey PRIMARY KEY (device_id, state_key);
ALTER TABLE digital_twins ADD CONSTRAINT ck_digital_twin_identity_xor
    CHECK ((binding_id IS NOT NULL) <> (legacy_capability_type IS NOT NULL));
ALTER TABLE digital_twins ADD CONSTRAINT ck_digital_twin_state_key CHECK (
    (binding_id IS NOT NULL AND state_key = 'binding:' || binding_id::text)
    OR (legacy_capability_type IS NOT NULL AND state_key = 'legacy:' || legacy_capability_type)
);

CREATE TABLE twin_state_transitions (
    transition_id UUID PRIMARY KEY,
    device_id UUID NOT NULL,
    state_key VARCHAR(200) NOT NULL,
    binding_id UUID,
    legacy_capability_type VARCHAR(100),
    capability_definition_id UUID,
    capability_key VARCHAR(100),
    external_identifier VARCHAR(255),
    subtarget_key VARCHAR(100) NOT NULL DEFAULT '',
    transition_type VARCHAR(40) NOT NULL,
    source VARCHAR(40) NOT NULL,
    value_type VARCHAR(20) NOT NULL,
    previous_numeric_value NUMERIC,
    previous_boolean_value BOOLEAN,
    previous_string_value TEXT,
    previous_object_value JSONB,
    new_numeric_value NUMERIC,
    new_boolean_value BOOLEAN,
    new_string_value TEXT,
    new_object_value JSONB,
    previous_unit VARCHAR(50),
    new_unit VARCHAR(50),
    event_time TIMESTAMPTZ,
    received_at TIMESTAMPTZ,
    requested_at TIMESTAMPTZ,
    accepted_for_dispatch_at TIMESTAMPTZ,
    transitioned_at TIMESTAMPTZ NOT NULL,
    correlation_id VARCHAR(255),
    command_id UUID,
    CONSTRAINT ck_twin_transition_identity_xor CHECK ((binding_id IS NOT NULL) <> (legacy_capability_type IS NOT NULL)),
    CONSTRAINT ck_twin_transition_type CHECK (transition_type IN ('REPORTED_STATE_CHANGED','DESIRED_STATE_CHANGED','DESIRED_STATE_CLEARED')),
    CONSTRAINT ck_twin_transition_source CHECK (source IN ('TELEMETRY','DEVICE_READBACK','MANUAL_COMMAND','RULE_ACTION','SYSTEM')),
    CONSTRAINT ck_twin_transition_value_type CHECK (value_type IN ('NUMBER','BOOLEAN','STRING','ENUM','OBJECT'))
);
CREATE INDEX idx_twin_transition_device_time ON twin_state_transitions(device_id, transitioned_at DESC, transition_id DESC);
CREATE INDEX idx_twin_transition_binding_time ON twin_state_transitions(device_id, binding_id, transitioned_at DESC, transition_id DESC)
    WHERE binding_id IS NOT NULL;
CREATE INDEX idx_twin_transition_legacy_time ON twin_state_transitions(device_id, legacy_capability_type, transitioned_at DESC, transition_id DESC)
    WHERE legacy_capability_type IS NOT NULL;
