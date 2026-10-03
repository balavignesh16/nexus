ALTER TABLE devices ADD COLUMN device_template_id UUID REFERENCES device_templates(id) ON DELETE RESTRICT;
ALTER TABLE devices ADD COLUMN device_template_version BIGINT;

CREATE TABLE device_capability_bindings (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    capability_definition_id UUID NOT NULL REFERENCES capability_definitions(id) ON DELETE RESTRICT,
    template_capability_id UUID REFERENCES device_template_capabilities(id) ON DELETE RESTRICT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    unit VARCHAR(50),
    configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    external_identifiers JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_device_capability_binding UNIQUE (device_id, capability_definition_id)
);
CREATE INDEX idx_capability_bindings_device ON device_capability_bindings(device_id);

UPDATE devices d SET
    device_template_id = t.id,
    device_template_version = t.version
FROM device_templates t
WHERE t.template_key = LOWER(REPLACE(d.name, '-', '_'))
  AND t.template_key IN ('esp32_sim_001','esp32_sim_002','esp32_sim_003');

INSERT INTO device_capability_bindings
    (id, device_id, capability_definition_id, template_capability_id, enabled, unit,
     configuration, external_identifiers)
SELECT dc.id, dc.device_id, cd.id, dtc.id, TRUE, dc.unit,
       COALESCE(dc.metadata, '{}'::jsonb),
       jsonb_build_object('telemetryKey', dc.capability_type, 'commandCapability', dc.capability_type)
FROM device_capabilities dc
JOIN capability_definitions cd ON cd.capability_key = LOWER(dc.capability_type)
JOIN devices d ON d.id = dc.device_id
LEFT JOIN device_template_capabilities dtc
  ON dtc.template_id = d.device_template_id AND dtc.capability_definition_id = cd.id;

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM device_capability_bindings) <> (SELECT COUNT(*) FROM device_capabilities) THEN
        RAISE EXCEPTION 'Every legacy device capability must map to exactly one generic binding';
    END IF;
END $$;
