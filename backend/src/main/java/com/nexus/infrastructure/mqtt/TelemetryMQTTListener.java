package com.nexus.infrastructure.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.adapter.core.AdapterMetrics;
import com.nexus.adapter.core.TransportGuard;
import com.nexus.adapter.mqtt.MqttJsonAdapter;
import com.nexus.adapter.mqtt.MqttJsonDecoder;
import com.nexus.adapter.mqtt.MqttTopics;
import com.nexus.adapter.spi.Decoded;
import com.nexus.adapter.spi.RawMessage;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.adapter.spi.TransportKind;
import com.nexus.device.command.DeviceCommandAckMessageHandler;
import com.nexus.device.connectivity.DeviceStatusMessageHandler;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.InvalidTelemetryException;
import com.nexus.telemetry.application.TelemetryMessageConflictException;
import com.nexus.telemetry.application.TelemetryService;
import com.nexus.telemetry.application.TelemetrySource;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TelemetryMQTTListener implements MqttCallbackExtended {
    private final java.util.concurrent.ThreadPoolExecutor subscriptions = new java.util.concurrent.ThreadPoolExecutor(
            0,1,10,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(1),task->{
                Thread thread=new Thread(task,"nexus-mqtt-subscriptions");thread.setDaemon(true);return thread;
            });
    volatile java.util.concurrent.Future<?> subscriptionTask=java.util.concurrent.CompletableFuture.completedFuture(null);
    @jakarta.annotation.PreDestroy
    public void closeSubscriptions() { subscriptions.shutdownNow(); }

    private static final Logger log = LoggerFactory.getLogger(TelemetryMQTTListener.class);

    private final MqttClient mqttClient;
    private final TelemetryService telemetryService;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final String telemetryTopic;
    private final MqttReconnectController reconnectController;
    private final MeterRegistry meterRegistry;

    // Tier 5B: the existing single MQTT connection/callback also carries
    // device-connectivity status messages (see the approved Tier 5B
    // architecture decision - a second MqttClient/reconnect controller was
    // explicitly rejected). All actual status logic lives in
    // DeviceStatusMessageHandler; this class only routes matching messages
    // to it and otherwise remains unaware of what it does.
    private final String deviceStatusTopic;
    private final DeviceStatusMessageHandler deviceStatusMessageHandler;

    // Tier 5E: command acknowledgements share this same single MQTT
    // connection/callback too (no second MqttClient/reconnect controller -
    // same architectural decision as Tier 5B's status routing above). All
    // actual ACK logic lives in DeviceCommandAckMessageHandler.
    private final String commandAckTopic;
    private final DeviceCommandAckMessageHandler deviceCommandAckMessageHandler;

    // Tier 3 Phase 4 message-level observability. "Received" means Paho
    // invoked messageArrived() - it does NOT mean successfully deserialized,
    // validated, or processed. This is deliberate: the purpose is detecting
    // broker/Paho -> application delivery failure (a CONNECTED backend that
    // silently receives nothing), a different question from data quality,
    // which remains covered by the existing log lines below.
    private Counter messagesReceived;
    private final AtomicLong lastMessageAtEpochMillis = new AtomicLong(0);
    private Counter subscribeFailures;

    // P5.3: telemetry decoding is the MQTT adapter's (extracted verbatim, differential-tested against the pre-extraction code). A listener built
    // by hand uses its own decoder over the same mapper and validator; in the application the adapter's decoder is used so that the adapter's
    // diagnostics see the production traffic. The transport guard (a device is served by the transport the server assigned it) and the adapter
    // meters are optional collaborators, exactly like the other late-injected ones in this codebase.
    private MqttJsonDecoder decoder;
    private MqttJsonAdapter adapter;
    private TransportGuard transportGuard;
    private AdapterMetrics adapterMetrics;

    @Autowired(required = false)
    void setMqttAdapter(MqttJsonAdapter adapter) {
        this.adapter = adapter;
        this.decoder = adapter.decoder();
    }

    @Autowired(required = false)
    void setTransportGuard(TransportGuard transportGuard) {
        this.transportGuard = transportGuard;
    }

    @Autowired(required = false)
    void setAdapterMetrics(AdapterMetrics adapterMetrics) {
        this.adapterMetrics = adapterMetrics;
    }

    public TelemetryMQTTListener(MqttClient mqttClient,
                                 TelemetryService telemetryService,
                                 ObjectMapper objectMapper,
                                 Validator validator,
                                 @Value("${mqtt.topic.telemetry}") String telemetryTopic,
                                 MqttReconnectController reconnectController,
                                 MeterRegistry meterRegistry,
                                 @Value("${mqtt.topic.status}") String deviceStatusTopic,
                                 DeviceStatusMessageHandler deviceStatusMessageHandler,
                                 @Value("${mqtt.topic.command-ack}") String commandAckTopic,
                                 DeviceCommandAckMessageHandler deviceCommandAckMessageHandler) {
        this.mqttClient = mqttClient;
        this.telemetryService = telemetryService;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.decoder = new MqttJsonDecoder(objectMapper, validator);
        this.telemetryTopic = telemetryTopic;
        this.reconnectController = reconnectController;
        this.meterRegistry = meterRegistry;
        this.deviceStatusTopic = deviceStatusTopic;
        this.deviceStatusMessageHandler = deviceStatusMessageHandler;
        this.commandAckTopic = commandAckTopic;
        this.deviceCommandAckMessageHandler = deviceCommandAckMessageHandler;
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
        try {
            // Tier 5B: subscribed on every connect/reconnect via this same
            // method, exactly like the telemetry topic above - this is what
            // makes retained device-status messages reliably redelivered on
            // every backend (re)connect, including after a restart.
            mqttClient.subscribe(deviceStatusTopic, 1);
            log.info("Subscribed to MQTT topic: {}", deviceStatusTopic);
        } catch (Exception e) {
            subscribeFailures.increment();
            log.error("Failed to subscribe to device status MQTT topic", e);
        }
        try {
            // Tier 5E: subscribed on every connect/reconnect, mirroring the
            // telemetry/status pattern above.
            mqttClient.subscribe(commandAckTopic, 1);
            log.info("Subscribed to MQTT topic: {}", commandAckTopic);
        } catch (Exception e) {
            subscribeFailures.increment();
            log.error("Failed to subscribe to command-ack MQTT topic", e);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        log.info("MQTT Connection complete (reconnect: {}). Subscribing...", reconnect);
        // Never block Paho's callback thread on synchronous SUBSCRIBE while queued incoming data waits on it.
        try { subscriptionTask=subscriptions.submit(this::subscribe); }
        catch(java.util.concurrent.RejectedExecutionException busy) { log.warn("MQTT subscription refresh already pending or shutting down"); }
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

        // Tier 5B: device-connectivity status messages share this same
        // callback/connection (see class-level note above). This is a thin,
        // early routing branch only - all actual status logic lives in
        // DeviceStatusMessageHandler, and the existing telemetry path below
        // is completely unreached and unmodified for a status message.
        if (topic.endsWith("/status")) {
            handleStatusMessage(topic, message);
            return;
        }

        // Tier 5E: command acknowledgements share this same callback/connection
        // (see class-level note above). Checked before the status suffix would
        // ever be reached for this shape since "/commands/ack" does not end
        // with "/status" - order between the two branches does not matter.
        if (topic.endsWith("/commands/ack")) {
            handleCommandAck(topic, message);
            return;
        }

        // 1. to 3. Decode. The MQTT adapter's decoder performs, in this order and with the same classification as before the extraction:
        // Jackson mapping (malformed JSON, or a JSON value of the wrong type for a field), Bean Validation (BEFORE any field is dereferenced, so a
        // missing required field is permanently invalid input rather than a NullPointerException), the legacy-shape check, and the topic/payload
        // device comparison. Anything it refuses can never become valid through broker redelivery: acknowledge and drop it.
        Decoded<TelemetryPayload> decoded = decoder.decodeTelemetry(new RawMessage(message.getPayload(), topic));
        if (decoded instanceof Decoded.Refused<TelemetryPayload> refused) {
            RejectionCode code = refused.rejection().code();
            logRefusal(topic, code);
            rejectedPermanently(code.name());
            if (adapter != null) adapter.recordTelemetry(false);
            adapterRejected(code);
            acknowledgeQuietly(message);
            return;
        }
        TelemetryPayload payload = ((Decoded.Accepted<TelemetryPayload>) decoded).value();
        TelemetryRequest request = new TelemetryRequest(payload.deviceId(), payload.timestamp(), payload.sensorType(), payload.value(),
                payload.unit(), payload.capabilityKey(), payload.readbackCommandId(), payload.messageId());
        if (adapter != null) adapter.recordTelemetry(true);

        // 4. TelemetryService Processing (Authoritative Persistence) and
        // 5. Manual ACK ONLY AFTER successful persistence. An identical duplicate (same messageId, same payload) returns
        // normally and is acknowledged without being processed again (R17). A failure of an unknown or infrastructural kind
        // (database, connection, lock, timeout, any unexpected exception) is treated as transient: do not ack, so the broker
        // redelivers this QoS 1 message later. Only the explicit, deterministic payload failures below are permanent
        // (decision A1); an arbitrary IllegalArgumentException from deeper layers is deliberately NOT one of them.
        try {
            if (transportRefused(request.deviceId(), topic, message)) return;
            long processingStarted = System.nanoTime();
            try {
                telemetryService.processTelemetry(request, TelemetrySource.MQTT);
            } finally {
                // Ingestion lag (contract R18, ledger P4-092): telemetry is processed inside the single MQTT callback, with no queue of its own,
                // so the time spent here is exactly what makes the broker's delivery queue grow. Measurement only; it changes no behaviour.
                if (meterRegistry != null)
                    meterRegistry.timer("nexus.telemetry.processing", "source", "MQTT").record(System.nanoTime() - processingStarted, java.util.concurrent.TimeUnit.NANOSECONDS);
            }
            log.debug("Successfully processed MQTT telemetry for device {}", request.deviceId());
            mqttClient.messageArrivedComplete(message.getId(), message.getQos());
        } catch (com.nexus.device.onboarding.OnboardingTelemetryRejectedException e) {
            log.warn("Rejected onboarding telemetry on topic {}: {}. Acknowledging and dropping.", topic, e.getMessage());
            rejectedPermanently(REASON_ONBOARDING_REJECTED);
            acknowledgeQuietly(message);
        } catch (InvalidTelemetryException e) {
            log.warn("Permanently invalid telemetry on topic {} ({}): {}. Acknowledging and dropping (not retryable).", topic, e.reason(), e.getMessage());
            rejectedPermanently(REASON_INVALID_TELEMETRY);
            acknowledgeQuietly(message);
        } catch (TelemetryMessageConflictException e) {
            log.warn("Conflicting duplicate messageId on topic {}: {}. Nothing stored; acknowledging and dropping (not retryable).", topic, e.getMessage());
            rejectedPermanently(REASON_DUPLICATE_CONFLICT);
            acknowledgeQuietly(message);
        } catch (Exception e) {
            log.error("Error processing MQTT message from topic {}. Did NOT ack. Message will be retried.", topic, e);
            throw new RuntimeException("MQTT processing failed, triggering retry", e);
        }
    }

    // Fixed, low-cardinality reasons for nexus.telemetry.permanent.rejections (never payload content or identifiers).
    static final String REASON_MALFORMED_JSON = "MALFORMED_JSON";
    static final String REASON_INVALID_MESSAGE_ID = "INVALID_MESSAGE_ID";
    static final String REASON_INVALID_FIELDS = "INVALID_FIELDS";
    static final String REASON_INVALID_LEGACY_PAYLOAD = "INVALID_LEGACY_PAYLOAD";
    static final String REASON_TOPIC_DEVICE_MISMATCH = "TOPIC_DEVICE_MISMATCH";
    static final String REASON_INVALID_TELEMETRY = "INVALID_TELEMETRY";
    static final String REASON_ONBOARDING_REJECTED = "ONBOARDING_REJECTED";
    static final String REASON_DUPLICATE_CONFLICT = "DUPLICATE_CONFLICT";

    private void rejectedPermanently(String reason) {
        Counter counter = meterRegistry == null ? null : meterRegistry.counter("nexus.telemetry.permanent.rejections", "reason", reason);
        if (counter != null) counter.increment();
    }

    /**
     * Tier 5B: own explicit ACK/error contract, independent of telemetry's.
     * DeviceStatusMessageHandler.handle() returns normally (without
     * throwing) for every permanently-invalid case it recognizes (malformed
     * topic, unrecognized payload, unknown device) - those are acknowledged
     * here, same as telemetry's own ack-and-drop categories. A genuine
     * transient failure inside the handler propagates as an exception, is
     * NOT acknowledged, and triggers QoS 1 redelivery, exactly like
     * telemetry's own processing-failure path above.
     */
    private void handleStatusMessage(String topic, MqttMessage message) {
        try {
            if (transportRefusedByTopic(topic, message)) return;
            deviceStatusMessageHandler.handle(topic, message.getPayload());
            mqttClient.messageArrivedComplete(message.getId(), message.getQos());
        } catch (Exception e) {
            log.error("Error processing device status message from topic {}. Did NOT ack. Message will be retried.", topic, e);
            throw new RuntimeException("Device status processing failed, triggering retry", e);
        }
    }

    /**
     * Tier 5E: own explicit ACK/error contract, independent of telemetry's
     * and device-status's - mirrors handleStatusMessage exactly.
     * DeviceCommandAckMessageHandler.handle() returns normally for every
     * permanently-invalid case it recognizes (malformed JSON/topic, unknown
     * commandId, device-binding mismatch); those are acknowledged here. A
     * genuine transient failure propagates as an exception, is NOT
     * acknowledged, and triggers QoS 1 redelivery.
     */
    private void handleCommandAck(String topic, MqttMessage message) {
        try {
            if (transportRefusedByTopic(topic, message)) return;
            deviceCommandAckMessageHandler.handle(topic, message.getPayload());
            mqttClient.messageArrivedComplete(message.getId(), message.getQos());
        } catch (Exception e) {
            log.error("Error processing command-ack message from topic {}. Did NOT ack. Message will be retried.", topic, e);
            throw new RuntimeException("Command-ack processing failed, triggering retry", e);
        }
    }

    static final String REASON_TRANSPORT_MISMATCH = "TRANSPORT_MISMATCH";

    /**
     * A device is served by the transport the server assigned it (decision D3). A message that arrives over MQTT for a device assigned to
     * another transport is permanently invalid input: acknowledged, counted and dropped, never processed. A device with no assignment (an
     * unknown device) is passed through, so the core refuses it with its existing wording. Without a guard (a hand-built listener) this is a
     * no-op, which is the behaviour before the assignment existed.
     */
    private boolean transportRefused(java.util.UUID deviceId, String topic, MqttMessage message) {
        if (transportGuard == null) return false;
        if (transportGuard.checkTransport(deviceId, TransportKind.MQTT_JSON).isEmpty()) return false;
        log.warn("Message on topic {} refused: the device is assigned to another transport. Acknowledging and dropping (not retryable).", topic);
        rejectedPermanently(REASON_TRANSPORT_MISMATCH);
        adapterRejected(RejectionCode.TRANSPORT_MISMATCH);
        acknowledgeQuietly(message);
        return true;
    }

    /** The same check for status and acknowledgement topics, whose device is the one in the topic; a topic without one is left to the handler. */
    private boolean transportRefusedByTopic(String topic, MqttMessage message) {
        if (transportGuard == null) return false;
        java.util.Optional<java.util.UUID> device = MqttTopics.anyDevice(topic);
        return device.isPresent() && transportRefused(device.get(), topic, message);
    }

    private void adapterRejected(RejectionCode code) {
        if (adapterMetrics == null) return;
        adapterMetrics.received(MqttJsonAdapter.ID, "rejected");
        adapterMetrics.rejected(MqttJsonAdapter.ID, code);
    }

    /** One log line per refusal, with the wording the listener always used (no payload text). */
    private void logRefusal(String topic, RejectionCode code) {
        switch (code) {
            case MALFORMED_JSON -> log.error("Malformed MQTT telemetry payload on topic {}. Acknowledging and dropping (not retryable).", topic);
            case INVALID_MESSAGE_ID -> log.error("Malformed MQTT telemetry payload on topic {} (invalid messageId). Acknowledging and dropping (not retryable).", topic);
            case INVALID_FIELDS -> log.warn("Invalid telemetry payload on topic {}. Acknowledging and dropping (not retryable).", topic);
            case INVALID_LEGACY_PAYLOAD -> log.warn("Invalid legacy telemetry payload on topic {}. Acknowledging and dropping (not retryable).", topic);
            case TOPIC_DEVICE_MISMATCH -> log.warn("Device ID mismatch on topic {}. Acknowledging and dropping.", topic);
            default -> log.warn("Refused telemetry on topic {} ({}). Acknowledging and dropping.", topic, code);
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
