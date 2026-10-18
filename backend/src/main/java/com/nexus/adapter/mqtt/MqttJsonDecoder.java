package com.nexus.adapter.mqtt;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.ConnectivityObservation;
import com.nexus.adapter.spi.Decoded;
import com.nexus.adapter.spi.RawMessage;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Decoding of the MQTT JSON wire contract, extracted from the telemetry listener and the ACK and status handlers. It reproduces their
 * classification exactly (the same Jackson mapping, the same Bean Validation, the same legacy-shape check and the same topic check,
 * in the same order) and a differential test against a reference copy of the pre-extraction code enforces that. A decoder never throws:
 * every bad input is a {@link com.nexus.adapter.spi.Rejection}.
 *
 * <p>One deliberate difference from the pre-extraction code is documented in the ledger: the JSON text {@code null} is MALFORMED_JSON here,
 * where the old listener threw out of the MQTT callback (a poison message that the broker redelivered). Out-of-range acknowledgement
 * timestamps are rejected here instead of throwing {@code DateTimeException}; the ACK handler itself is a core class and is unchanged.
 */
public class MqttJsonDecoder {
    private final ObjectMapper mapper;
    private final Validator validator;

    public MqttJsonDecoder(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    // ------------------------------------------------------------------ telemetry

    public Decoded<TelemetryPayload> decodeTelemetry(RawMessage message) {
        TelemetryRequest request;
        try {
            request = mapper.readValue(message.payload(), TelemetryRequest.class);
        } catch (IOException e) {
            boolean badMessageId = e instanceof JsonMappingException mapping
                    && mapping.getPath().stream().anyMatch(reference -> "messageId".equals(reference.getFieldName()));
            return Decoded.refused(badMessageId ? RejectionCode.INVALID_MESSAGE_ID : RejectionCode.MALFORMED_JSON);
        } catch (RuntimeException e) {
            return Decoded.refused(RejectionCode.MALFORMED_JSON);
        }
        if (request == null) return Decoded.refused(RejectionCode.MALFORMED_JSON);

        Set<ConstraintViolation<TelemetryRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            return Decoded.refused(violations.stream().anyMatch(v -> "messageId".equals(v.getPropertyPath().toString()))
                    ? RejectionCode.INVALID_MESSAGE_ID : RejectionCode.INVALID_FIELDS);
        }

        boolean generic = request.capabilityKey() != null && !request.capabilityKey().isBlank();
        if (!generic && (request.sensorType() == null || request.sensorType().isBlank()
                || request.unit() == null || request.unit().isBlank()
                || !(request.value() instanceof Number))) {
            return Decoded.refused(RejectionCode.INVALID_LEGACY_PAYLOAD);
        }

        String topic = message.topic();
        if (topic != null) {
            Optional<String> topicDevice = MqttTopics.telemetryDeviceSegment(topic);
            if (topicDevice.isPresent() && !topicDevice.get().equals(request.deviceId().toString())) {
                return Decoded.refused(RejectionCode.TOPIC_DEVICE_MISMATCH);
            }
        }
        return Decoded.accepted(new TelemetryPayload(request.deviceId(), request.timestamp(), request.sensorType(), request.value(),
                request.unit(), request.capabilityKey(), request.readbackCommandId(), request.messageId()));
    }

    // ------------------------------------------------------------------ acknowledgement

    public Decoded<AckPayload> decodeAcknowledgement(RawMessage message) {
        if (message.topic() == null || MqttTopics.ackDevice(message.topic()).isEmpty()) return Decoded.refused(RejectionCode.INVALID_FIELDS);
        JsonNode node;
        try {
            node = mapper.readTree(message.payload());
        } catch (IOException | RuntimeException e) {
            return Decoded.refused(RejectionCode.MALFORMED_JSON);
        }
        if (node == null || !node.hasNonNull("commandId") || !node.get("commandId").isTextual()) return Decoded.refused(RejectionCode.INVALID_FIELDS);
        UUID commandId;
        try {
            commandId = UUID.fromString(node.get("commandId").asText());
        } catch (IllegalArgumentException e) {
            return Decoded.refused(RejectionCode.INVALID_FIELDS);
        }
        if (!node.hasNonNull("status") || !node.get("status").isTextual()) return Decoded.refused(RejectionCode.INVALID_FIELDS);
        AckPayload.Status status;
        try {
            status = AckPayload.Status.valueOf(node.get("status").asText());
        } catch (IllegalArgumentException e) {
            return Decoded.refused(RejectionCode.INVALID_FIELDS);
        }
        if (!node.hasNonNull("timestamp") || !node.get("timestamp").isIntegralNumber()) return Decoded.refused(RejectionCode.INVALID_FIELDS);
        Instant deviceTimestamp;
        try {
            deviceTimestamp = Instant.ofEpochSecond(node.get("timestamp").asLong());
        } catch (DateTimeException e) {
            return Decoded.refused(RejectionCode.INVALID_FIELDS);
        }
        String reason = node.hasNonNull("reason") ? node.get("reason").asText() : null;
        return Decoded.accepted(new AckPayload(commandId, status, deviceTimestamp, reason));
    }

    // ------------------------------------------------------------------ connectivity (plain-text status)

    public Decoded<ConnectivityObservation> decodeConnectivity(RawMessage message) {
        Optional<UUID> device = message.topic() == null ? Optional.empty() : MqttTopics.statusDevice(message.topic());
        if (device.isEmpty()) return Decoded.refused(RejectionCode.INVALID_FIELDS);
        String text = new String(message.payload(), StandardCharsets.UTF_8).trim();
        if ("online".equals(text)) return Decoded.accepted(new ConnectivityObservation(device.get(), ConnectivityObservation.State.ONLINE));
        if ("offline".equals(text)) return Decoded.accepted(new ConnectivityObservation(device.get(), ConnectivityObservation.State.OFFLINE));
        return Decoded.refused(RejectionCode.INVALID_FIELDS);
    }
}
