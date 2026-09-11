package com.nexus.infrastructure.health;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes a small, curated platform-health contract under the existing
 * /api/ namespace (so it inherits the existing CORS configuration - unlike
 * /actuator/health, which is not reachable from the browser).
 *
 * Reuses existing, already-correct signals rather than inventing new health
 * checks:
 * - database status is read from Spring Boot Actuator's own "db" health
 *   contributor (a real datasource validity check), not re-implemented here.
 * - mqtt status is read directly from the existing MqttClient bean's live
 *   isConnected() state - the NEXUS backend's actual connection to the
 *   broker, not an inference from an unrelated request.
 *
 * Deliberately does not report on Digital Twin, Rule Engine, or Action
 * Engine: those are application modules inside this same process, not
 * independently-failing components, so there is nothing honest to check.
 */
@RestController
@RequestMapping("/api/v1")
public class PlatformHealthController {

    private final HealthEndpoint healthEndpoint;
    private final MqttClient mqttClient;

    public PlatformHealthController(HealthEndpoint healthEndpoint, MqttClient mqttClient) {
        this.healthEndpoint = healthEndpoint;
        this.mqttClient = mqttClient;
    }

    @GetMapping("/platform-health")
    public PlatformHealthResponse platformHealth() {
        return new PlatformHealthResponse(resolveDatabaseStatus(), resolveMqttStatus());
    }

    private String resolveDatabaseStatus() {
        HealthComponent db = healthEndpoint.healthForPath("db");
        if (db != null && Status.UP.equals(db.getStatus())) {
            return "UP";
        }
        return "DOWN";
    }

    private String resolveMqttStatus() {
        return mqttClient.isConnected() ? "CONNECTED" : "DISCONNECTED";
    }
}
