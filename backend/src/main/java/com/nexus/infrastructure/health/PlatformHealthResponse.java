package com.nexus.infrastructure.health;

/**
 * Minimal, NEXUS-owned platform health contract exposed to the frontend.
 *
 * Deliberately narrower than the full Actuator health payload: only the
 * two signals that are independently, directly observable in this process
 * (datasource validity, MQTT broker connection) are exposed. No credentials,
 * connection strings, hostnames, or other actuator internals are included.
 *
 * @param database "UP" or "DOWN" - the backend datasource's current validity,
 *                 as determined by Spring Boot Actuator's own health check.
 * @param mqtt     "CONNECTED" or "DISCONNECTED" - the NEXUS backend MQTT
 *                 client's current connection to the broker. This reflects
 *                 broker connectivity only, not whether telemetry is flowing.
 */
public record PlatformHealthResponse(String database, String mqtt) {
}
