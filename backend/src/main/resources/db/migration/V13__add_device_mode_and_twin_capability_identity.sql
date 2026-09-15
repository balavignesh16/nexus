-- V13__add_device_mode_and_twin_capability_identity.sql
--
-- Phase 11: hardware device catalog + SIM/REAL architecture.

-- 1. Device.mode - column is NOT NULL; API-level omission defaults to
-- SIMULATED in application code (DeviceService), not at the DB layer.
ALTER TABLE devices ADD COLUMN mode VARCHAR(20);
UPDATE devices SET mode = 'SIMULATED' WHERE mode IS NULL;
ALTER TABLE devices ALTER COLUMN mode SET NOT NULL;

-- 2. Digital Twin: device_id -> (device_id, latest_sensor_type) identity,
-- so a device reporting multiple sensor types (e.g. a BME280's
-- TEMPERATURE/HUMIDITY/PRESSURE) retains each capability's latest reading
-- independently instead of the newest sensorType silently overwriting the
-- others. No defensive backfill for latest_sensor_type: every production
-- write path (DigitalTwinService.updateTwin) already guarantees a
-- non-blank sensorType, sourced from TelemetryRequest.sensorType(), which
-- is @NotBlank-validated. A synthetic value is deliberately not
-- introduced - if this ALTER ever fails on a real database, that means a
-- genuine, unexplained data integrity issue exists and must be
-- investigated manually, not silently papered over.
ALTER TABLE digital_twins ALTER COLUMN latest_sensor_type SET NOT NULL;
ALTER TABLE digital_twins DROP CONSTRAINT digital_twins_pkey;
ALTER TABLE digital_twins ADD CONSTRAINT digital_twins_pkey PRIMARY KEY (device_id, latest_sensor_type);
