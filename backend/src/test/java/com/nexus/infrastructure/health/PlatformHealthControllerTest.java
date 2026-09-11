package com.nexus.infrastructure.health;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests PlatformHealthController's mapping from the existing Actuator "db"
 * health contributor and the existing MqttClient bean's connection state
 * into the small NEXUS-owned health contract. Both dependencies are mocked -
 * no real database or broker is required.
 */
class PlatformHealthControllerTest {

    private HealthEndpoint healthEndpoint;
    private MqttClient mqttClient;
    private PlatformHealthController controller;

    @BeforeEach
    void setUp() {
        healthEndpoint = mock(HealthEndpoint.class);
        mqttClient = mock(MqttClient.class);
        controller = new PlatformHealthController(healthEndpoint, mqttClient);
    }

    @Test
    void databaseUp_mqttConnected_reportsUpAndConnected() {
        when(healthEndpoint.healthForPath("db")).thenReturn(Health.up().build());
        when(mqttClient.isConnected()).thenReturn(true);

        PlatformHealthResponse response = controller.platformHealth();

        assertEquals("UP", response.database());
        assertEquals("CONNECTED", response.mqtt());
    }

    @Test
    void databaseDown_mqttConnected_reportsDownAndConnected() {
        when(healthEndpoint.healthForPath("db")).thenReturn(Health.down().build());
        when(mqttClient.isConnected()).thenReturn(true);

        PlatformHealthResponse response = controller.platformHealth();

        assertEquals("DOWN", response.database());
        assertEquals("CONNECTED", response.mqtt());
    }

    @Test
    void databaseUp_mqttDisconnected_reportsUpAndDisconnected() {
        when(healthEndpoint.healthForPath("db")).thenReturn(Health.up().build());
        when(mqttClient.isConnected()).thenReturn(false);

        PlatformHealthResponse response = controller.platformHealth();

        assertEquals("UP", response.database());
        assertEquals("DISCONNECTED", response.mqtt());
    }

    @Test
    void databaseDown_mqttDisconnected_reportsBothUnhealthy() {
        when(healthEndpoint.healthForPath("db")).thenReturn(Health.down().build());
        when(mqttClient.isConnected()).thenReturn(false);

        PlatformHealthResponse response = controller.platformHealth();

        assertEquals("DOWN", response.database());
        assertEquals("DISCONNECTED", response.mqtt());
    }

    @Test
    void databaseContributorMissing_reportsDownRatherThanThrowing() {
        when(healthEndpoint.healthForPath("db")).thenReturn(null);
        when(mqttClient.isConnected()).thenReturn(true);

        PlatformHealthResponse response = controller.platformHealth();

        assertEquals("DOWN", response.database());
    }

    @Test
    void databaseStatusOtherThanUp_reportsDown() {
        // e.g. Status.UNKNOWN or Status.OUT_OF_SERVICE - anything not UP is DOWN.
        when(healthEndpoint.healthForPath("db")).thenReturn(Health.status(Status.UNKNOWN).build());
        when(mqttClient.isConnected()).thenReturn(true);

        PlatformHealthResponse response = controller.platformHealth();

        assertEquals("DOWN", response.database());
    }
}
