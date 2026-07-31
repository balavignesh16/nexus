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

import jakarta.annotation.PreDestroy;

@Configuration
public class MQTTConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MQTTConfiguration.class);

    @Value("${mqtt.broker.url}")
    private String brokerUrl;

    @Value("${mqtt.client.id}")
    private String clientId;

    private MqttClient mqttClient;

    @Bean
    public MqttClient mqttClient() throws MqttException {
        log.info("Connecting to MQTT broker at {} with clientId {}", brokerUrl, clientId);
        mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        
        try {
            mqttClient.connect(options);
            log.info("Connected successfully to MQTT broker.");
        } catch (MqttException e) {
            log.warn("Failed to connect to MQTT broker at startup. It will retry if automaticReconnect is true, or we are in a test environment.", e);
        }
        
        return mqttClient;
    }

    @PreDestroy
    public void disconnect() {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
                log.info("Disconnected from MQTT broker.");
            } catch (MqttException e) {
                log.error("Error disconnecting from MQTT broker", e);
            }
        }
    }
}
