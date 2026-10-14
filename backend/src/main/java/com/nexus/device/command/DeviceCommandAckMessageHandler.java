package com.nexus.device.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.domain.AcknowledgementStatus;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.realtime.RealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import com.nexus.twin.application.VerificationService;
import com.nexus.device.domain.DeviceRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tier 5E: all command-acknowledgement logic lives here, isolated from
 * TelemetryMQTTListener (which only routes messages on the
 * nexus/devices/{deviceId}/commands/ack topic to {@link #handle(String, byte[])}
 * and otherwise remains unaware of what this class does) - mirroring the
 * existing DeviceStatusMessageHandler isolation pattern from Tier 5B.
 *
 * commandId (from the payload) is the sole correlation key back to the
 * ActionHistoryRecord already persisted by Tier 5D's synchronous command
 * dispatch (requestId == commandId). deviceId is taken authoritatively from
 * the topic, never the payload, and cross-checked against the correlated
 * record before anything is applied - an ACK from Device B must never
 * acknowledge a command sent to Device A.
 *
 * ACK/error contract (mirrors, but is independent of, TelemetryMQTTListener's
 * own): a permanently invalid message (malformed JSON, missing/invalid
 * commandId, unknown commandId, device-binding mismatch) is logged and this
 * method returns normally - the caller acknowledges it, exactly like
 * telemetry's/status's own "ack and drop" category. A genuine transient
 * failure (e.g. the repository throws) is NOT caught here - it propagates
 * so the caller does not acknowledge, allowing QoS 1 redelivery to retry.
 */
@Component
public class DeviceCommandAckMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceCommandAckMessageHandler.class);

    private final ActionHistoryRepository actionHistoryRepository;
    private final RealtimePublisher realtimePublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final DeviceRepository devices;
    private VerificationService verification;

    @Autowired
    void setVerification(VerificationService verification){this.verification=verification;}
    private com.nexus.rule.trigger.RuleSourceFacts ruleFacts;
    @Autowired
    void setRuleFacts(com.nexus.rule.trigger.RuleSourceFacts ruleFacts){this.ruleFacts=ruleFacts;}

    public DeviceCommandAckMessageHandler(ActionHistoryRepository actionHistoryRepository,
                                           RealtimePublisher realtimePublisher,
                                           ObjectMapper objectMapper) {
        this(actionHistoryRepository,realtimePublisher,objectMapper,Clock.systemUTC(),null);
    }

    public DeviceCommandAckMessageHandler(ActionHistoryRepository actionHistoryRepository,
                                           RealtimePublisher realtimePublisher,
                                           ObjectMapper objectMapper, Clock clock) {
        this(actionHistoryRepository,realtimePublisher,objectMapper,clock,null);
    }

    @Autowired
    public DeviceCommandAckMessageHandler(ActionHistoryRepository actionHistoryRepository,
                                           RealtimePublisher realtimePublisher,
                                           ObjectMapper objectMapper, Clock clock,DeviceRepository devices) {
        this.actionHistoryRepository = actionHistoryRepository;
        this.realtimePublisher = realtimePublisher;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.devices = devices;
    }

    @Transactional
    public void handle(String topic, byte[] payload) {
        Instant backendReceivedAt = clock.instant();
        UUID topicDeviceId = parseDeviceIdOrNull(topic);
        if (topicDeviceId == null) {
            log.warn("Malformed command-ack topic '{}'. Acknowledging and dropping (not retryable).", topic);
            return;
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (IOException e) {
            log.warn("Malformed command-ack JSON on topic {}. Acknowledging and dropping (not retryable).", topic);
            return;
        }

        UUID commandId = parseCommandIdOrNull(node);
        if (commandId == null) {
            log.warn("Command-ack on topic {} is missing a valid commandId. Acknowledging and dropping (not retryable).", topic);
            return;
        }

        AcknowledgementStatus status = parseStatusOrNull(node);
        if (status == null) {
            log.warn("Command-ack {} on topic {} has a missing/unrecognized status. Acknowledging and dropping (not retryable).", commandId, topic);
            return;
        }

        Instant ackReceivedAt = parseTimestampOrNull(node);
        if (ackReceivedAt == null) {
            log.warn("Command-ack {} on topic {} is missing a valid timestamp. Acknowledging and dropping (not retryable).", commandId, topic);
            return;
        }
        String reason = node.hasNonNull("reason") ? node.get("reason").asText() : null;

        if(devices!=null && devices.findByIdForUpdate(topicDeviceId).isEmpty()) {
            log.warn("Command-ack for unknown device {} ignored",topicDeviceId);
            return;
        }

        ActionHistoryRecord record = actionHistoryRepository.findById(commandId).orElse(null);
        if (record == null) {
            log.warn("Command-ack for unknown commandId {}. Acknowledging and dropping (not retryable); no row fabricated.", commandId);
            return;
        }

        if (!topicDeviceId.equals(record.getDeviceId())) {
            log.warn("Command-ack device mismatch: ack arrived on topic for device {} but commandId {} belongs to device {}. Acknowledging and dropping; not applied.",
                    topicDeviceId, commandId, record.getDeviceId());
            return;
        }

        // Update ACK fields only: a concurrent publish-result update must not
        // erase this ACK, and an ACK never changes the current desired row.
        if (record.getAckStatus() != null) {
            if (record.getAckStatus() != status || !ackReceivedAt.equals(record.getAckReceivedAt())
                    || !java.util.Objects.equals(reason, record.getAckMessage()))
                log.warn("Conflicting duplicate ACK for command {}; first ACK preserved",commandId);
            return;
        }
        if (actionHistoryRepository.updateAcknowledgement(commandId, topicDeviceId,
                status, ackReceivedAt, backendReceivedAt, reason) == 0) {
            log.warn("Guarded ACK update skipped for command {}; first ACK preserved",commandId);
            return;
        }

        // Only the first authoritative ACK (guarded update above) reaches here, so duplicates cannot create a second fact.
        if(status==AcknowledgementStatus.REJECTED && ruleFacts!=null)
            ruleFacts.ackRejected(commandId,topicDeviceId,backendReceivedAt,reason);
        if(status==AcknowledgementStatus.REJECTED && verification!=null)
            verification.commandFailed(topicDeviceId,commandId,"DEVICE_COMMAND_REJECTED");
        Runnable publish=()->{try{realtimePublisher.publishActionAcknowledged(commandId, topicDeviceId, status, reason, ackReceivedAt);}
            catch(RuntimeException realtimeFailure){log.error("Post-commit ACK realtime publication failed command={}",commandId,realtimeFailure);}};
        if(TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){publish.run();}
            });
        else publish.run();
        log.info("Command {} acknowledged by device {}: {}", commandId, topicDeviceId, status);
    }

    private UUID parseDeviceIdOrNull(String topic) {
        String[] parts = topic.split("/");
        // Expected shape: nexus/devices/{deviceId}/commands/ack -> 5 segments.
        if (parts.length != 5 || !"commands".equals(parts[3]) || !"ack".equals(parts[4])) {
            return null;
        }
        try {
            return UUID.fromString(parts[2]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private UUID parseCommandIdOrNull(JsonNode node) {
        if (!node.hasNonNull("commandId") || !node.get("commandId").isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(node.get("commandId").asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private AcknowledgementStatus parseStatusOrNull(JsonNode node) {
        if (!node.hasNonNull("status") || !node.get("status").isTextual()) {
            return null;
        }
        try {
            return AcknowledgementStatus.valueOf(node.get("status").asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Instant parseTimestampOrNull(JsonNode node) {
        if (!node.hasNonNull("timestamp") || !node.get("timestamp").isIntegralNumber()) {
            return null;
        }
        return Instant.ofEpochSecond(node.get("timestamp").asLong());
    }
}
