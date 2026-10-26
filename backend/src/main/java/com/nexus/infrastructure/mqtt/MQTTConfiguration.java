package com.nexus.infrastructure.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MQTTConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MQTTConfiguration.class);

    // Extra margin beyond connectionTimeout to bound MqttClient's synchronous
    // connect() wait (see setTimeToWait below). Not exposed as a property:
    // it is a fixed safety margin, not an operational tuning knob (Tier 3
    // Phase 2C).
    private static final long TIME_TO_WAIT_GRACE_MS = 5000L;

    @Value("${mqtt.broker.url}")
    private String brokerUrl;

    @Value("${mqtt.client.id}")
    private String clientId;

    @Bean
    public MqttConnectOptions mqttConnectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        // Reconnection is now owned entirely by MqttReconnectController; Paho's
        // own automaticReconnect is disabled so there is exactly one reconnect
        // mechanism (Tier 3 Phase 2).
        options.setAutomaticReconnect(false);
        // Set clean session to false to retain QoS 1 messages while disconnected
        options.setCleanSession(false);
        options.setConnectionTimeout(10);
        return options;
    }

    @Bean
    public MqttClient mqttClient(MqttConnectOptions options) throws MqttException {
        log.info("Connecting to MQTT broker at {} with clientId {}", brokerUrl, clientId);
        MqttClient mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        // Set manual acks to true to prevent data loss on persistence failure
        mqttClient.setManualAcks(true);

        // Bound how long the synchronous connect() wrapper blocks the calling
        // thread. Without this, a Paho 1.2.5 ConnectBG thread that never
        // completes (confirmed defect - Tier 3 Phase 1) would block the
        // caller (this bean's startup thread, and later MqttReconnectController's
        // scheduler thread) forever (Tier 3 Phase 2B). This does NOT fix that
        // underlying Paho defect - it only bounds NEXUS's own exposure to it.
        long timeToWaitMs = (options.getConnectionTimeout() * 1000L) + TIME_TO_WAIT_GRACE_MS;
        mqttClient.setTimeToWait(timeToWaitMs);

        // The reconnect controller connects after ContextRefreshedEvent, once the intake callback exists.
        // A persistent session may deliver QoS1 immediately on CONNECT; manual ACKs must have an owner first.
        log.info("MQTT client prepared; connection deferred until intake callbacks are installed.");

        return mqttClient;
    }
}
