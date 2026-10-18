-- Operator procedure for the HTTP adapter's device credentials. This is NOT a Flyway migration and is never applied automatically:
-- creating database roles is a deliberate operations step (adapter-contract.md section 9.8.1).
--
-- Run it once, as a database administrator, after V41 has been applied, replacing the two placeholders:
--   {{ADMIN_PASSWORD}}  the password of the new login role nexus_credential_admin (used only by the nexus-credentials tool)
--   {{BACKEND_ROLE}}    the existing login role the NEXUS backend connects with
--
-- nexus_credential_admin can create, rotate, revoke, list and audit credentials and can see which devices exist, and nothing else: no access to
-- telemetry, commands, rules or any other table, no DELETE anywhere, and no way to change a device.
-- The backend role can read credentials (to verify a presented key) and update ONLY the last-contact instant; it cannot create, alter or delete a
-- credential, so a compromised backend cannot mint a key.

CREATE ROLE nexus_credential_admin LOGIN PASSWORD '{{ADMIN_PASSWORD}}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
GRANT USAGE ON SCHEMA public TO nexus_credential_admin;
GRANT SELECT ON devices TO nexus_credential_admin;
GRANT SELECT, INSERT, UPDATE ON device_ingestion_credentials TO nexus_credential_admin;
GRANT SELECT, INSERT ON device_credential_events TO nexus_credential_admin;

GRANT SELECT ON device_ingestion_credentials TO {{BACKEND_ROLE}};
GRANT UPDATE (last_valid_contact_at) ON device_ingestion_credentials TO {{BACKEND_ROLE}};
