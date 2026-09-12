package com.nexus.infrastructure.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.TelemetryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TelemetryMQTTListener implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(TelemetryMQTTListener.class);

    private final MqttClient mqttClient;
    private final TelemetryService telemetryService;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final String telemetryTopic;
    private final MqttReconnectController reconnectController;
    private final MeterRegistry meterRegistry;

    // Tier 3 Phase 4 message-level observability. "Received" means Paho
    // invoked messageArrived() - it does NOT mean successfully deserialized,
    // validated, or processed. This is deliberate: the purpose is detecting
    // broker/Paho -> application delivery failure (a CONNECTED backend that
    // silently receives nothing), a different question from data quality,
    // which remains covered by the existing log lines below.
    private Counter messagesReceived;
    private final AtomicLong lastMessageAtEpochMillis = new AtomicLong(0);
    private Counter subscribeFailures;

    public TelemetryMQTTListener(MqttClient mqttClient,
                                 TelemetryService telemetryService,
                                 ObjectMapper objectMapper,
                                 Validator validator,
                                 @Value("${mqtt.topic.telemetry}") String telemetryTopic,
                                 MqttReconnectController reconnectController,
                                 MeterRegistry meterRegistry) {
        this.mqttClient = mqttClient;
        this.telemetryService = telemetryService;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.telemetryTopic = telemetryTopic;
        this.reconnectController = reconnectController;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        this.messagesReceived = meterRegistry.counter("mqtt.messages.received");
        this.subscribeFailures = meterRegistry.counter("mqtt.subscribe.failures");
        meterRegistry.gauge("mqtt.messages.seconds.since.last", this, TelemetryMQTTListener::secondsSinceLastMessage);

        if (mqttClient != null) {
            mqttClient.setCallback(this);
            // Connect completes before init if successful, so connectComplete may not trigger if already connected
            // But we can check isConnected
            if (mqttClient.isConnected()) {
                subscribe();
            }
        }
    }

    private double secondsSinceLastMessage() {
        long lastAt = lastMessageAtEpochMillis.get();
        if (lastAt == 0) {
            return -1;
        }
        return (System.currentTimeMillis() - lastAt) / 1000.0;
    }

    private void subscribe() {
        try {
            // Hardcode QoS 1 as requested
            mqttClient.subscribe(telemetryTopic, 1);
            log.info("Subscribed to MQTT topic: {}", telemetryTopic);
        } catch (Exception e) {
            subscribeFailures.increment();
            log.error("Failed to subscribe to MQTT topic", e);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        log.info("MQTT Connection complete (reconnect: {}). Subscribing...", reconnect);
        subscribe();
        reconnectController.onConnectComplete();
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost", cause);
        reconnectController.onConnectionLost(cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // Tier 3 Phase 4: count and timestamp every Paho callback invocation,
        // before any deserialization/validation/processing, and regardless
        // of their outcome - this metric answers "is Paho handing us
        // messages at all", not "is the data good".
        messagesReceived.increment();
        lastMessageAtEpochMillis.set(System.currentTimeMillis());

        log.debug("Received MQTT message on topic: {}", topic);

        // 1. Deserialize. A payload that cannot be parsed - malformed JSON
        // syntax, or a JSON value of the wrong type for a field - can never
        // become valid through broker redelivery. Treat it as permanently
        // invalid input: acknowledge and drop it rather than letting the
        // broker retry it forever.
        TelemetryRequest request;
        try {
            request = objectMapper.readValue(message.getPayload(), TelemetryRequest.class);
        } catch (IOException e) {
            log.error("Malformed MQTT telemetry payload on topic {}. Acknowledging and dropping (not retryable).", topic, e);
            acknowledgeQuietly(message);
            return;
        }

        // 2. Bean Validation. This runs BEFORE any field of `request` is
        // dereferenced - in particular before the topic/payload device-id
        // comparison below - so a payload with a missing/null required field
        // (e.g. no "deviceId" at all) is rejected here as permanently invalid
        // input instead of causing a NullPointerException further down.
        Set<ConstraintViolation<TelemetryRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            log.warn("Invalid telemetry payload on topic {}: {}. Acknowledging and dropping (not retryable).", topic, violations);
            acknowledgeQuietly(message);
            return;
        }

        // 3. Topic vs Payload Validation
        String[] parts = topic.split("/");
        if (parts.length >= 3) {
            String topicDeviceId = parts[2];
            if (!topicDeviceId.equals(request.deviceId().toString())) {
                log.warn("Device ID mismatch! Topic ID: {}, Payload ID: {}. Acknowledging and dropping.", topicDeviceId, request.deviceId());
                acknowledgeQuietly(message);
                return;
            }
        }

        // 4. TelemetryService Processing (Authoritative Persistence) and
        // 5. Manual ACK ONLY AFTER successful persistence. A failure here is
        // treated as transient: do not ack, so the broker redelivers this
        // QoS 1 message later.
        try {
            telemetryService.processTelemetry(request);
            log.debug("Successfully processed MQTT telemetry for device {}", request.deviceId());
            mqttClient.messageArrivedComplete(message.getId(), message.getQos());
        } catch (Exception e) {
            log.error("Error processing MQTT message from topic {}. Did NOT ack. Message will be retried.", topic, e);
            throw new RuntimeException("MQTT processing failed, triggering retry", e);
        }
    }

    /**
     * Acknowledges a message that has been positively identified as
     * permanently invalid (unparseable, fails validation, or a topic/payload
     * device-id mismatch) so it is dropped rather than redelivered forever.
     * If the ack call itself fails, that failure is logged and swallowed
     * rather than escalated to a retry - retrying will not make permanently
     * invalid input valid.
     */
    private void acknowledgeQuietly(MqttMessage message) {
        try {
            mqttClient.messageArrivedComplete(message.getId(), message.getQos());
        } catch (MqttException e) {
            log.error("Failed to acknowledge a permanently-invalid MQTT message (id {}); it may be redelivered despite being unprocessable.", message.getId(), e);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Not used for subscribers
    }
}
