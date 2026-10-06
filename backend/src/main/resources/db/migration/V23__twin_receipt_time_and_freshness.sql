-- P2.3: durable backend receipt time and optional per-binding freshness narrowing.
ALTER TABLE digital_twins ADD COLUMN received_at TIMESTAMPTZ;
ALTER TABLE device_capability_bindings ADD COLUMN freshness_threshold_seconds INTEGER;
ALTER TABLE device_capability_bindings ADD CONSTRAINT ck_binding_freshness_positive
    CHECK (freshness_threshold_seconds IS NULL OR freshness_threshold_seconds > 0);
