package com.nexus.infrastructure.mqtt;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;

import java.net.ServerSocket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Tier 3 Phase 2 startup-lifecycle requirement: an unreachable
 * MQTT broker at application startup must not hang Spring context
 * initialization (the bounded MqttClient.timeToWait applies identically to
 * the initial connect, not only to controller-triggered retries - Phase 2C
 * section 3), and the existing non-fatal startup semantics (the mqttClient
 * bean still exists, the application still comes up) must remain intact.
 *
 * Uses a narrow, hand-built Spring context containing only the MQTT beans
 * under test, rather than the full application context, so this stays fast
 * and does not depend on unrelated infrastructure (database, etc).
 */
class MqttStartupTimeoutTest {

    @Configuration
    static class TestConfig {

        @Bean
        static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        MqttReconnectController mqttReconnectController(MqttClient mqttClient, MqttConnectOptions options, MeterRegistry registry) {
            return new MqttReconnectController(mqttClient, options, registry);
        }
    }

    @Test
    @Timeout(30)
    void contextStartsWithoutHangingWhenBrokerIsUnreachable() throws Exception {
        int unreachablePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            unreachablePort = probe.getLocalPort();
        } // closed immediately afterwards - nothing listens on this port now

        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        // MQTTConfiguration must be registered directly as a @Configuration
        // class (not wrapped in a plain @Bean factory method) so Spring's
        // configuration-class processing discovers its own @Bean methods
        // (mqttConnectOptions, mqttClient).
        ctx.register(TestConfig.class, MQTTConfiguration.class);
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "mqtt.broker.url", "tcp://localhost:" + unreachablePort,
                "mqtt.client.id", "startup-timeout-test-client",
                "mqtt.topic.telemetry", "nexus/devices/+/telemetry"
        )));

        long start = System.currentTimeMillis();
        ctx.refresh();
        long elapsed = System.currentTimeMillis() - start;

        try {
            assertTrue(elapsed < 20_000,
                    "context refresh must not hang waiting on an unreachable broker; took " + elapsed + "ms");
            assertNotNull(ctx.getBean(MqttClient.class),
                    "the MqttClient bean must still exist despite the failed initial connect (existing non-fatal startup semantics)");

            MqttReconnectController controller = ctx.getBean(MqttReconnectController.class);
            assertNotNull(controller);
            assertEquals(MqttReconnectController.State.RETRY_WAIT, controller.getState(),
                    "the controller must take over and schedule a retry, since Paho's own automaticReconnect is now disabled");
        } finally {
            ctx.close();
        }
    }
}
