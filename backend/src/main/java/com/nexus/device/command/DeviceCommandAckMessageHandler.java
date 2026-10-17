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
    /** P4.4: durable ACK facts and the database clock. Absent in unit tests that build the handler by hand; then the legacy behaviour applies. */
    private com.nexus.action.outbox.CommandAckFacts ackFacts;
    @Autowired(required=false)
    void setAckFacts(com.nexus.action.outbox.CommandAckFacts ackFacts){this.ackFacts=ackFacts;}
    private io.micrometer.core.instrument.MeterRegistry meters;
    @Autowired(required=false)
    void setMeters(io.micrometer.core.instrument.MeterRegistry meters){this.meters=meters;}

    /** Fixed, low-cardinality outcome tags only (never an identifier). */
    private void count(String outcome){
        if(meters!=null)meters.counter("nexus.command.ack","outcome",outcome).increment();
    }

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
        // The receipt time that is compared with the persisted ACK deadline must come from the SAME (database) clock that set the
        // deadline; an application clock that is wrong in either direction must not make an ACK late or early.
        Instant backendReceivedAt = ackFacts != null ? ackFacts.databaseNow() : clock.instant();
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
            duplicate(record.getAckStatus(), record.getAckReceivedAt(), record.getAckMessage(), commandId, status, ackReceivedAt, reason, backendReceivedAt);
            return;
        }
        if (actionHistoryRepository.updateAcknowledgement(commandId, topicDeviceId,
                status, ackReceivedAt, backendReceivedAt, reason) == 0) {
            // Another transaction recorded the first ACK between the read above and this guarded update. Re-read the committed ACK
            // (the persistence context still holds the stale entity) and classify this one as an identical or conflicting duplicate.
            var first = ackFacts == null ? null : ackFacts.firstAck(commandId);
            if (first != null) duplicate(first.status(), first.deviceTimestamp(), first.message(), commandId, status, ackReceivedAt, reason, backendReceivedAt);
            else log.warn("Guarded ACK update skipped for command {}; first ACK preserved",commandId);
            return;
        }
        // The guarded update above holds the action_history row lock until commit, so the history is classified under that lock: the
        // timeout scanner (which needs the same row lock) cannot append ACK_TIMED_OUT between the check and the fact.
        if (ackFacts != null) {
            var snapshot = ackFacts.snapshot(commandId);
            var lateness = ackFacts.appendFirstAck(commandId, status == AcknowledgementStatus.REJECTED, reason, snapshot, backendReceivedAt, commandId.toString());
            count(lateness == com.nexus.action.outbox.CommandAckFacts.Lateness.AFTER_TIMEOUT ? "late_after_timeout"
                    : lateness == com.nexus.action.outbox.CommandAckFacts.Lateness.AFTER_TERMINAL ? "late_after_terminal"
                    : status == AcknowledgementStatus.REJECTED ? "rejected" : "accepted");
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

    /**
     * A second ACK for a command that already has one. Identical content changes nothing (a counter is incremented); a different content
     * never overwrites the first (no last-write-wins) and is recorded once per distinct occurrence as ACK_CONFLICT_IGNORED, bounded per command.
     */
    private void duplicate(AcknowledgementStatus firstStatus, Instant firstTimestamp, String firstMessage, UUID commandId,
                           AcknowledgementStatus status, Instant ackReceivedAt, String reason, Instant backendReceivedAt) {
        boolean identical = firstStatus == status && ackReceivedAt.equals(firstTimestamp) && java.util.Objects.equals(reason, firstMessage);
        if (identical) {
            count("duplicate");
            log.debug("Identical duplicate ACK for command {} ignored",commandId);
            return;
        }
        count("conflict");
        log.warn("Conflicting duplicate ACK for command {}; first ACK preserved",commandId);
        if (ackFacts != null)
            ackFacts.appendConflict(commandId, ackFacts.snapshot(commandId), backendReceivedAt, commandId.toString(), firstStatus.name(), status.name());
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
