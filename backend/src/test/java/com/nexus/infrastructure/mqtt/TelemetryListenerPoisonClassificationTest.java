package com.nexus.infrastructure.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.device.command.DeviceCommandAckMessageHandler;
import com.nexus.device.connectivity.DeviceStatusMessageHandler;
import com.nexus.device.onboarding.OnboardingTelemetryRejectedException;
import com.nexus.shared.exception.ResourceNotFoundException;
import com.nexus.telemetry.api.dto.TelemetryResponse;
import com.nexus.telemetry.application.InvalidTelemetryException;
import com.nexus.telemetry.application.TelemetryMessageConflictException;
import com.nexus.telemetry.application.TelemetryService;
import com.nexus.telemetry.application.TelemetrySource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Decision A1 (ledger P4-080 and the poison-message defect found in the review rehearsal): the MQTT listener acknowledges and
 * drops only explicitly identified, deterministic payload failures, records a fixed-reason metric, and never loops on them;
 * every database, connection, lock, timeout and unknown failure stays unacknowledged so the broker redelivers it. The service
 * is a mock, so each exception type is injected exactly.
 */
class TelemetryListenerPoisonClassificationTest {
    private static final String COUNTER = "nexus.telemetry.permanent.rejections";

    private MqttClient mqttClient;
    private TelemetryService telemetryService;
    private SimpleMeterRegistry meters;
    private TelemetryMQTTListener listener;
    private UUID deviceId;
    private String topic;

    @BeforeEach
    void setUp() {
        mqttClient = mock(MqttClient.class);
        telemetryService = mock(TelemetryService.class);
        meters = new SimpleMeterRegistry();
        listener = new TelemetryMQTTListener(mqttClient, telemetryService, new ObjectMapper().registerModule(new JavaTimeModule()),
                Validation.buildDefaultValidatorFactory().getValidator(), "nexus/devices/+/telemetry", mock(MqttReconnectController.class), meters,
                "nexus/devices/+/status", mock(DeviceStatusMessageHandler.class), "nexus/devices/+/commands/ack", mock(DeviceCommandAckMessageHandler.class));
        listener.init();
        deviceId = UUID.randomUUID();
        topic = "nexus/devices/" + deviceId + "/telemetry";
    }

    private MqttMessage message(String extraFields) {
        MqttMessage message = new MqttMessage(("{\"deviceId\":\"" + deviceId + "\",\"timestamp\":\"" + Instant.parse("2026-10-17T12:00:00Z")
                + "\",\"capabilityKey\":\"motion\",\"value\":true" + extraFields + "}").getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        message.setId(7);
        return message;
    }

    private double rejections(String reason) {
        var counter = meters.find(COUNTER).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private void assertPermanent(Throwable failure, String reason) throws Exception {
        when(telemetryService.processTelemetry(any(), eq(TelemetrySource.MQTT))).thenThrow((RuntimeException) failure);
        assertDoesNotThrow(() -> listener.messageArrived(topic, message("")), failure.getClass().getSimpleName() + " must not loop");
        verify(mqttClient).messageArrivedComplete(7, 1);
        assertEquals(1, rejections(reason), "fixed-reason metric for " + failure.getClass().getSimpleName());
    }

    private void assertTransient(Throwable failure) throws Exception {
        when(telemetryService.processTelemetry(any(), eq(TelemetrySource.MQTT))).thenThrow((RuntimeException) failure);
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> listener.messageArrived(topic, message("")), failure.getClass().getSimpleName());
        assertEquals(failure, thrown.getCause(), "the original failure is preserved so Paho does not acknowledge");
        verify(mqttClient, never()).messageArrivedComplete(anyInt(), anyInt());
        assertEquals(0, meters.find(COUNTER).counters().stream().mapToDouble(c -> c.count()).sum(), "a transient failure is never counted as a permanent rejection");
    }

    // ---- permanent: acknowledge and drop, recorded, no retry loop ----

    @Test
    void aDeterministicValidationFailureIsAcknowledgedDroppedAndCounted() throws Exception {
        assertPermanent(new InvalidTelemetryException("VALUE_TYPE_MISMATCH", "Value does not match capability value type"), "INVALID_TELEMETRY");
    }

    @Test
    void aConflictingDuplicateIsAcknowledgedDroppedAndCounted() throws Exception {
        assertPermanent(new TelemetryMessageConflictException(deviceId, "m-1"), "DUPLICATE_CONFLICT");
    }

    @Test
    void aRejectedOnboardingSampleIsAcknowledgedDroppedAndCounted() throws Exception {
        assertPermanent(new OnboardingTelemetryRejectedException("rejected"), "ONBOARDING_REJECTED");
    }

    @Test
    void anIdenticalDuplicateReturnsNormallyAndIsAcknowledgedWithoutAnyRejection() throws Exception {
        when(telemetryService.processTelemetry(any(), eq(TelemetrySource.MQTT)))
                .thenReturn(new TelemetryResponse(null, deviceId, Instant.now(), null, null, null, "motion", null, true).asDuplicate());
        assertDoesNotThrow(() -> listener.messageArrived(topic, message(",\"messageId\":\"m-1\"")));
        verify(mqttClient).messageArrivedComplete(7, 1);
        assertEquals(0, rejections("DUPLICATE_CONFLICT") + rejections("INVALID_TELEMETRY"));
    }

    @Test
    void anInvalidMessageIdIsAcknowledgedDroppedAndNeverReachesTheService() throws Exception {
        for (String bad : new String[] {",\"messageId\":123", ",\"messageId\":true", ",\"messageId\":\"has space\"", ",\"messageId\":\"\"", ",\"messageId\":\"" + "x".repeat(65) + "\""}) {
            assertDoesNotThrow(() -> listener.messageArrived(topic, message(bad)), bad);
        }
        verify(telemetryService, never()).processTelemetry(any(), any());
        verify(mqttClient, org.mockito.Mockito.times(5)).messageArrivedComplete(7, 1);
        assertEquals(5, rejections("INVALID_MESSAGE_ID"));
    }

    @Test
    void malformedJsonIsAcknowledgedDroppedAndCounted() throws Exception {
        MqttMessage broken = new MqttMessage("{not json".getBytes(StandardCharsets.UTF_8));
        broken.setQos(1);
        broken.setId(7);
        assertDoesNotThrow(() -> listener.messageArrived(topic, broken));
        verify(mqttClient).messageArrivedComplete(7, 1);
        assertEquals(1, rejections("MALFORMED_JSON"));
    }

    // ---- transient and unknown: never acknowledged, so the broker redelivers ----

    @Test
    void databaseConnectionLockTimeoutAndUnknownFailuresStayRetryable() throws Exception {
        List<Throwable> transientFailures = List.of(
                new DataAccessResourceFailureException("database unreachable", new SQLException("connection refused", "08001")),
                new CannotCreateTransactionException("no connection from the pool"),
                new CannotAcquireLockException("lock contention"),
                new QueryTimeoutException("statement timeout"),
                new IllegalStateException("temporary service failure"),
                new NullPointerException("unexpected"),
                new RuntimeException("unknown"),
                new ResourceNotFoundException("Device", "unknown-yet"));
        for (Throwable failure : transientFailures) {
            setUp();
            assertTransient(failure);
        }
    }

    @Test
    void anArbitraryIllegalArgumentExceptionFromADeeperLayerIsNotTreatedAsPermanent() throws Exception {
        // decision A1: only the typed validation failures are permanent; a bare IllegalArgumentException may come from anywhere
        assertTransient(new IllegalArgumentException("thrown by some other layer"));
    }
}
