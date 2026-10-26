package com.nexus.adapter;

import com.nexus.action.domain.AcknowledgementStatus;
import com.nexus.adapter.core.AcknowledgementIntakeBridge;
import com.nexus.adapter.core.AdapterMetrics;
import com.nexus.adapter.core.ConnectivityIntakeBridge;
import com.nexus.adapter.core.TelemetryIntakeBridge;
import com.nexus.adapter.core.TransportAssignments;
import com.nexus.adapter.core.TransportGuard;
import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.ConnectivityObservation;
import com.nexus.adapter.spi.IntakeResult;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.adapter.spi.TransportContext;
import com.nexus.adapter.spi.TransportKind;
import com.nexus.device.command.DeviceCommandAckMessageHandler;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.connectivity.DeviceStatusMessageHandler;
import com.nexus.device.onboarding.OnboardingTelemetryRejectedException;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.InvalidTelemetryException;
import com.nexus.telemetry.application.TelemetryMessageConflictException;
import com.nexus.telemetry.application.TelemetryService;
import com.nexus.telemetry.application.TelemetrySource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Delegation to the unchanged core entry points (P5-035), the assignment check before any port call (P5-036) and device mismatch (P5-038). */
class IntakeBridgeTest {
    TransportAssignments assignments;
    TelemetryService telemetry;
    DeviceCommandAckMessageHandler ackHandler;
    DeviceStatusMessageHandler statusHandler;
    SimpleMeterRegistry meters;
    TelemetryIntakeBridge telemetryBridge;
    AcknowledgementIntakeBridge ackBridge;
    ConnectivityIntakeBridge connectivityBridge;
    UUID device;

    @BeforeEach
    void setUp() {
        assignments = mock(TransportAssignments.class);
        telemetry = mock(TelemetryService.class);
        ackHandler = mock(DeviceCommandAckMessageHandler.class);
        statusHandler = mock(DeviceStatusMessageHandler.class);
        meters = new SimpleMeterRegistry();
        AdapterMetrics metrics = new AdapterMetrics(meters);
        TransportGuard guard = new TransportGuard(assignments);
        telemetryBridge = new TelemetryIntakeBridge(telemetry, guard, metrics);
        ackBridge = new AcknowledgementIntakeBridge(ackHandler, guard, metrics);
        connectivityBridge = new ConnectivityIntakeBridge(statusHandler, guard, metrics);
        device = UUID.randomUUID();
        when(assignments.assignedTo(device)).thenReturn(Optional.of(TransportKind.MQTT_JSON));
    }

    private TransportContext context(TransportKind transport, UUID deviceId) {
        return new TransportContext(transport == TransportKind.MQTT_JSON ? "mqtt-json" : "http-webhook", transport, deviceId, Instant.now(), "test");
    }

    private TelemetryPayload sample(UUID deviceId) {
        return new TelemetryPayload(deviceId, Instant.parse("2026-01-01T00:00:00Z"), "TEMPERATURE_SENSOR", 24.5, "CELSIUS", null, null, "m-1");
    }

    @Test
    void p5_035_telemetryIsHandedToTheUnchangedServiceExactlyOnceWithTheDecodedRequestAndTheRightLabel() {
        assertTrue(telemetryBridge.submit(sample(device), context(TransportKind.MQTT_JSON, device)).isAccepted());
        ArgumentCaptor<TelemetryRequest> request = ArgumentCaptor.forClass(TelemetryRequest.class);
        verify(telemetry).processTelemetry(request.capture(), org.mockito.ArgumentMatchers.eq(TelemetrySource.MQTT));
        assertEquals(device, request.getValue().deviceId());
        assertEquals(24.5, request.getValue().value());
        assertEquals("m-1", request.getValue().messageId());
        assertEquals("CELSIUS", request.getValue().unit());
        assertEquals(1.0, meters.get("nexus.adapter.received").tag("adapter", "mqtt-json").tag("result", "accepted").counter().count());

        UUID httpDevice = UUID.randomUUID();
        when(assignments.assignedTo(httpDevice)).thenReturn(Optional.of(TransportKind.HTTP_WEBHOOK));
        telemetryBridge.submit(sample(httpDevice), context(TransportKind.HTTP_WEBHOOK, httpDevice));
        verify(telemetry).processTelemetry(any(), org.mockito.ArgumentMatchers.eq(TelemetrySource.HTTP));
    }

    @Test
    void p5_035_acknowledgementAndConnectivityAreHandedToTheSameApplicationStepsAsTheMqttPath() {
        UUID command = UUID.randomUUID();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        assertTrue(ackBridge.submit(new AckPayload(command, AckPayload.Status.REJECTED, at, "STALE"), context(TransportKind.MQTT_JSON, device)).isAccepted());
        verify(ackHandler).acknowledge(device, command, AcknowledgementStatus.REJECTED, at, "STALE");
        assertTrue(ackBridge.submit(new AckPayload(command, AckPayload.Status.ACCEPTED, at, null), context(TransportKind.MQTT_JSON, device)).isAccepted());
        verify(ackHandler).acknowledge(device, command, AcknowledgementStatus.ACCEPTED, at, null);

        assertTrue(connectivityBridge.submit(new ConnectivityObservation(device, ConnectivityObservation.State.ONLINE), context(TransportKind.MQTT_JSON, device)).isAccepted());
        verify(statusHandler).observe(device, DeviceConnectivityStatus.ONLINE);
        connectivityBridge.submit(new ConnectivityObservation(device, ConnectivityObservation.State.OFFLINE), context(TransportKind.MQTT_JSON, device));
        verify(statusHandler).observe(device, DeviceConnectivityStatus.OFFLINE);
    }

    @Test
    void p5_036_aMessageFromAnAdapterOtherThanTheAssignedTransportIsRefusedBeforeAnyPortCall() {
        IntakeResult telemetryResult = telemetryBridge.submit(sample(device), context(TransportKind.HTTP_WEBHOOK, device));
        IntakeResult ackResult = ackBridge.submit(new AckPayload(UUID.randomUUID(), AckPayload.Status.ACCEPTED, Instant.now(), null), context(TransportKind.HTTP_WEBHOOK, device));
        IntakeResult statusResult = connectivityBridge.submit(new ConnectivityObservation(device, ConnectivityObservation.State.ONLINE), context(TransportKind.HTTP_WEBHOOK, device));
        for (IntakeResult result : new IntakeResult[]{telemetryResult, ackResult, statusResult}) {
            var refused = (IntakeResult.Refused) result;
            assertEquals(RejectionCode.TRANSPORT_MISMATCH, refused.rejection().code());
            assertEquals(com.nexus.adapter.spi.Rejection.Classification.PERMANENT, refused.rejection().classification());
        }
        verifyNoInteractions(telemetry, ackHandler, statusHandler);
        assertEquals(3.0, meters.get("nexus.adapter.rejected").tag("adapter", "http-webhook").tag("reason", "TRANSPORT_MISMATCH").counter().count());
    }

    @Test
    void p5_038_aPayloadDeviceDifferentFromTheAuthenticatedOrTopicDeviceIsRejectedAndNothingIsProcessed() {
        UUID other = UUID.randomUUID();
        var result = (IntakeResult.Refused) telemetryBridge.submit(sample(other), context(TransportKind.MQTT_JSON, device));
        assertEquals(RejectionCode.DEVICE_MISMATCH, result.rejection().code());
        var status = (IntakeResult.Refused) connectivityBridge.submit(new ConnectivityObservation(other, ConnectivityObservation.State.ONLINE), context(TransportKind.MQTT_JSON, device));
        assertEquals(RejectionCode.DEVICE_MISMATCH, status.rejection().code());
        verifyNoInteractions(telemetry, ackHandler, statusHandler);
    }

    @Test
    void anUnknownDeviceHasNoAssignmentAndIsPassedToTheCoreWhichRefusesItWithItsOwnWording() {
        UUID unknown = UUID.randomUUID();
        when(assignments.assignedTo(unknown)).thenReturn(Optional.empty());
        assertTrue(telemetryBridge.submit(sample(unknown), context(TransportKind.MQTT_JSON, unknown)).isAccepted());
        verify(telemetry).processTelemetry(any(), any());
    }

    @Test
    void theThreeDeterministicCoreRefusalsAreFixedCodesAndInfrastructureFailuresPropagate() {
        org.mockito.Mockito.doThrow(new OnboardingTelemetryRejectedException("no")).when(telemetry).processTelemetry(any(), any());
        assertEquals(RejectionCode.ONBOARDING_REJECTED, ((IntakeResult.Refused) telemetryBridge.submit(sample(device), context(TransportKind.MQTT_JSON, device))).rejection().code());
        org.mockito.Mockito.doThrow(new InvalidTelemetryException("bad", "REASON")).when(telemetry).processTelemetry(any(), any());
        assertEquals(RejectionCode.INVALID_TELEMETRY, ((IntakeResult.Refused) telemetryBridge.submit(sample(device), context(TransportKind.MQTT_JSON, device))).rejection().code());
        org.mockito.Mockito.doThrow(new TelemetryMessageConflictException(device, "m-1")).when(telemetry).processTelemetry(any(), any());
        assertEquals(RejectionCode.DUPLICATE_CONFLICT, ((IntakeResult.Refused) telemetryBridge.submit(sample(device), context(TransportKind.MQTT_JSON, device))).rejection().code());
        CannotAcquireLockException transientFailure = new CannotAcquireLockException("lock");
        org.mockito.Mockito.doThrow(transientFailure).when(telemetry).processTelemetry(any(), any());
        assertSame(transientFailure, assertThrows(CannotAcquireLockException.class, () -> telemetryBridge.submit(sample(device), context(TransportKind.MQTT_JSON, device))),
                "a transient failure is not a result: the adapter must not acknowledge, so the transport redelivers");
    }

    @Test
    void theSimulatorKeepsTheMqttDiagnosticLabelBecauseV40OnlyKnowsMqttAndHttp() {
        UUID simulated = UUID.randomUUID();
        when(assignments.assignedTo(simulated)).thenReturn(Optional.of(TransportKind.SIMULATOR));
        telemetryBridge.submit(sample(simulated), new TransportContext("simulator", TransportKind.SIMULATOR, simulated, Instant.now(), "sim"));
        verify(telemetry).processTelemetry(any(), org.mockito.ArgumentMatchers.eq(TelemetrySource.MQTT));
        verify(telemetry, never()).processTelemetry(any(), org.mockito.ArgumentMatchers.eq(TelemetrySource.HTTP));
    }
}
