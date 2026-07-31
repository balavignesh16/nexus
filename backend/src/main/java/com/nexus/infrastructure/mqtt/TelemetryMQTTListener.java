package com.nexus.infrastructure.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.TelemetryService;
import jakarta.annotation.PostConstruct;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class TelemetryMQTTListener implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(TelemetryMQTTListener.class);

    private final MqttClient mqttClient;
    private final TelemetryService telemetryService;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final String telemetryTopic;

    public TelemetryMQTTListener(MqttClient mqttClient, 
                                 TelemetryService telemetryService, 
                                 ObjectMapper objectMapper,
                                 Validator validator,
                                 @Value("${mqtt.topic.telemetry}") String telemetryTopic) {
        this.mqttClient = mqttClient;
        this.telemetryService = telemetryService;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.telemetryTopic = telemetryTopic;
    }

    @PostConstruct
    public void init() {
        if (mqttClient != null) {
            mqttClient.setCallback(this);
            // Connect completes before init if successful, so connectComplete may not trigger if already connected
            // But we can check isConnected
            if (mqttClient.isConnected()) {
                subscribe();
            }
        }
    }

    private void subscribe() {
        try {
            // Hardcode QoS 1 as requested
            mqttClient.subscribe(telemetryTopic, 1);
            log.info("Subscribed to MQTT topic: {}", telemetryTopic);
        } catch (Exception e) {
            log.error("Failed to subscribe to MQTT topic", e);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        log.info("MQTT Connection complete (reconnect: {}). Subscribing...", reconnect);
        subscribe();
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost", cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            log.debug("Received MQTT message on topic: {}", topic);
            
            // 1. Deserialize
            byte[] payload = message.getPayload();
            TelemetryRequest request = objectMapper.readValue(payload, TelemetryRequest.class);

            // 2. Topic vs Payload Validation
            String[] parts = topic.split("/");
            if (parts.length >= 3) {
                String topicDeviceId = parts[2];
                if (!topicDeviceId.equals(request.deviceId().toString())) {
                    log.warn("Device ID mismatch! Topic ID: {}, Payload ID: {}. Dropping message.", topicDeviceId, request.deviceId());
                    return; // Reject message
                }
            }

            // 3. Bean Validation
            Set<ConstraintViolation<TelemetryRequest>> violations = validator.validate(request);
            if (!violations.isEmpty()) {
                log.warn("Invalid telemetry payload: {}", violations);
                throw new ConstraintViolationException(violations);
            }

            // 4. TelemetryService Processing
            telemetryService.processTelemetry(request);
            log.debug("Successfully processed MQTT telemetry for device {}", request.deviceId());

        } catch (Exception e) {
            log.error("Error processing MQTT message from topic {}", topic, e);
            // In a production system, this could go to a Dead Letter Queue. For M4, we just drop.
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Not used for subscribers
    }
}
