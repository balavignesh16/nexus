package com.nexus.device.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.domain.AcknowledgementStatus;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.realtime.RealtimePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DeviceCommandAckMessageHandler (Tier 5E) - all actual
 * command-acknowledgement logic lives here, isolated from
 * TelemetryMQTTListener (whose own routing-isolation tests live in
 * TelemetryMQTTListenerTest).
 */
class DeviceCommandAckMessageHandlerTest {

    private ActionHistoryRepository actionHistoryRepository;
    private RealtimePublisher realtimePublisher;
    private DeviceCommandAckMessageHandler handler;

    @BeforeEach
    void setUp() {
        actionHistoryRepository = mock(ActionHistoryRepository.class);
        when(actionHistoryRepository.updateAcknowledgement(any(), any(), any(), any(), any(), nullable(String.class)))
                .thenReturn(1);
        realtimePublisher = mock(RealtimePublisher.class);
        ObjectMapper objectMapper = new ObjectMapper();
        handler = new DeviceCommandAckMessageHandler(actionHistoryRepository, realtimePublisher, objectMapper);
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private ActionHistoryRecord existingRecord(UUID requestId, UUID deviceId) {
        return new ActionHistoryRecord(requestId, null, deviceId, ActionType.SEND_COMMAND, ExecutionStatus.SUCCESS,
                Map.of("deviceId", deviceId.toString(), "capability", "LED", "command", "SET_STATE", "value", true),
                Instant.now(), "Command published to MQTT");
    }

    // ---------- 1. valid ACCEPTED ACK ----------

    @Test
    void validAcceptedAck_appliesToRecord_andBroadcasts() {
        UUID deviceId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/commands/ack";
        ActionHistoryRecord record = existingRecord(commandId, deviceId);
        when(actionHistoryRepository.findById(commandId)).thenReturn(Optional.of(record));

        String payload = "{\"commandId\":\"" + commandId + "\",\"status\":\"ACCEPTED\",\"timestamp\":1735689600}";
        handler.handle(topic, bytes(payload));

        verify(actionHistoryRepository).updateAcknowledgement(eq(commandId), eq(deviceId),
                eq(AcknowledgementStatus.ACCEPTED), eq(Instant.ofEpochSecond(1735689600)), any(), nullable(String.class));
        verify(realtimePublisher).publishActionAcknowledged(eq(commandId), eq(deviceId), eq(AcknowledgementStatus.ACCEPTED), any(), any());
    }

    // ---------- 2. valid REJECTED ACK ----------

    @Test
    void validRejectedAck_appliesReasonToRecord() {
        UUID deviceId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/commands/ack";
        ActionHistoryRecord record = existingRecord(commandId, deviceId);
        when(actionHistoryRepository.findById(commandId)).thenReturn(Optional.of(record));

        String payload = "{\"commandId\":\"" + commandId + "\",\"status\":\"REJECTED\",\"timestamp\":1735689600,\"reason\":\"STALE\"}";
        handler.handle(topic, bytes(payload));

        verify(actionHistoryRepository).updateAcknowledgement(eq(commandId), eq(deviceId),
                eq(AcknowledgementStatus.REJECTED), eq(Instant.ofEpochSecond(1735689600)), any(), eq("STALE"));
        verify(realtimePublisher).publishActionAcknowledged(eq(commandId), eq(deviceId), eq(AcknowledgementStatus.REJECTED), eq("STALE"), any());
    }

    // ---------- 3. unknown commandId ----------

    @Test
    void unknownCommandId_isNotAppliedAnywhere_noRowFabricated() {
        UUID deviceId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/commands/ack";
        when(actionHistoryRepository.findById(commandId)).thenReturn(Optional.empty());

        String payload = "{\"commandId\":\"" + commandId + "\",\"status\":\"ACCEPTED\",\"timestamp\":1735689600}";
        assertDoesNotThrow(() -> handler.handle(topic, bytes(payload)));

        verify(actionHistoryRepository, never()).save(any());
        verify(realtimePublisher, never()).publishActionAcknowledged(any(), any(), any(), any(), any());
    }

    // ---------- 4. malformed commandId (not a UUID) ----------

    @Test
    void malformedCommandId_isAckAndDropped() {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/commands/ack";

        String payload = "{\"commandId\":\"not-a-uuid\",\"status\":\"ACCEPTED\",\"timestamp\":1735689600}";
        assertDoesNotThrow(() -> handler.handle(topic, bytes(payload)));

        verify(actionHistoryRepository, never()).findById(any());
        verify(actionHistoryRepository, never()).save(any());
    }

    // ---------- 5. missing commandId ----------

    @Test
    void missingCommandId_isAckAndDropped() {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/commands/ack";

        String payload = "{\"status\":\"ACCEPTED\",\"timestamp\":1735689600}";
        assertDoesNotThrow(() -> handler.handle(topic, bytes(payload)));

        verify(actionHistoryRepository, never()).findById(any());
    }

    // ---------- 6. wrong deviceId (device binding) ----------

    @Test
    void wrongDeviceId_doesNotApplyAck_notModified() {
        UUID commandDeviceId = UUID.randomUUID();
        UUID differentTopicDeviceId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        String topic = "nexus/devices/" + differentTopicDeviceId + "/commands/ack";
        ActionHistoryRecord record = existingRecord(commandId, commandDeviceId);
        when(actionHistoryRepository.findById(commandId)).thenReturn(Optional.of(record));

        String payload = "{\"commandId\":\"" + commandId + "\",\"status\":\"ACCEPTED\",\"timestamp\":1735689600}";
        handler.handle(topic, bytes(payload));

        assertNull(record.getAckStatus());
        verify(actionHistoryRepository, never()).save(any());
        verify(realtimePublisher, never()).publishActionAcknowledged(any(), any(), any(), any(), any());
    }

    // ---------- 7. duplicate ACK ----------

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void duplicateAck_isIdempotent_noDuplicatePersistenceRow(CapturedOutput output) {
        UUID deviceId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/commands/ack";
        ActionHistoryRecord record = existingRecord(commandId, deviceId);
        ActionHistoryRecord acknowledged = existingRecord(commandId,deviceId);
        acknowledged.applyAcknowledgement(AcknowledgementStatus.ACCEPTED,Instant.ofEpochSecond(1735689600),null);
        when(actionHistoryRepository.findById(commandId)).thenReturn(Optional.of(record),Optional.of(acknowledged),
                Optional.of(acknowledged));

        String payload = "{\"commandId\":\"" + commandId + "\",\"status\":\"ACCEPTED\",\"timestamp\":1735689600}";
        handler.handle(topic, bytes(payload));
        handler.handle(topic, bytes(payload));
        handler.handle(topic, bytes(payload.replace("ACCEPTED","REJECTED")));

        verify(actionHistoryRepository, times(1)).updateAcknowledgement(eq(commandId), eq(deviceId),
                eq(AcknowledgementStatus.ACCEPTED), eq(Instant.ofEpochSecond(1735689600)), any(), nullable(String.class));
        verify(actionHistoryRepository, times(3)).findById(commandId);
        verify(realtimePublisher,times(1)).publishActionAcknowledged(eq(commandId),eq(deviceId),
                eq(AcknowledgementStatus.ACCEPTED),any(),any());
        org.junit.jupiter.api.Assertions.assertTrue(output.getAll().contains("Conflicting duplicate ACK for command "+commandId));
    }

    // ---------- 11. malformed JSON ----------

    @Test
    void malformedJson_isAckAndDropped_doesNotThrow() {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/commands/ack";

        assertDoesNotThrow(() -> handler.handle(topic, bytes("{not valid json")));

        verify(actionHistoryRepository, never()).findById(any());
    }

    // ---------- 12. repository transient failure propagates ----------

    @Test
    void repositoryTransientFailure_propagates_forCallerToNotAckMqttMessage() {
        UUID deviceId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/commands/ack";
        ActionHistoryRecord record = existingRecord(commandId, deviceId);
        when(actionHistoryRepository.findById(commandId)).thenReturn(Optional.of(record));
        when(actionHistoryRepository.updateAcknowledgement(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("simulated DB failure"));

        String payload = "{\"commandId\":\"" + commandId + "\",\"status\":\"ACCEPTED\",\"timestamp\":1735689600}";
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> handler.handle(topic, bytes(payload)));
    }

    // ---------- malformed topic ----------

    @Test
    void malformedTopic_isAckAndDropped() {
        assertDoesNotThrow(() -> handler.handle("nexus/devices/not-enough-segments", bytes("{}")));
        verify(actionHistoryRepository, never()).findById(any());
    }

    // ---------- missing/invalid status ----------

    @Test
    void missingStatus_isAckAndDropped() {
        UUID deviceId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/commands/ack";

        String payload = "{\"commandId\":\"" + commandId + "\",\"timestamp\":1735689600}";
        assertDoesNotThrow(() -> handler.handle(topic, bytes(payload)));

        verify(actionHistoryRepository, never()).findById(any());
    }

    @Test
    void unrecognizedStatusValue_isAckAndDropped() {
        UUID deviceId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/commands/ack";

        String payload = "{\"commandId\":\"" + commandId + "\",\"status\":\"MAYBE\",\"timestamp\":1735689600}";
        assertDoesNotThrow(() -> handler.handle(topic, bytes(payload)));

        verify(actionHistoryRepository, never()).findById(any());
    }

    // ---------- missing timestamp ----------

    @Test
    void missingTimestamp_isAckAndDropped() {
        UUID deviceId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/commands/ack";

        String payload = "{\"commandId\":\"" + commandId + "\",\"status\":\"ACCEPTED\"}";
        assertDoesNotThrow(() -> handler.handle(topic, bytes(payload)));

        verify(actionHistoryRepository, never()).findById(any());
    }
}
