package com.nexus.device.connectivity;

import com.nexus.device.domain.DeviceRepository;
import com.nexus.realtime.RealtimePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DeviceStatusMessageHandler (Tier 5B) - all actual
 * device-connectivity logic lives here, isolated from
 * TelemetryMQTTListener (whose own routing-isolation tests live in
 * TelemetryMQTTListenerTest).
 */
class DeviceStatusMessageHandlerTest {

    private DeviceRepository deviceRepository;
    private DeviceConnectivityRegistry connectivityRegistry;
    private RealtimePublisher realtimePublisher;
    private DeviceStatusMessageHandler handler;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        connectivityRegistry = mock(DeviceConnectivityRegistry.class);
        realtimePublisher = mock(RealtimePublisher.class);
        handler = new DeviceStatusMessageHandler(deviceRepository, connectivityRegistry, realtimePublisher);
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---------- known device, valid payload ----------

    @Test
    void knownDevice_onlinePayload_persistsAndBroadcasts() {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/status";
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(connectivityRegistry.applyObservedStatus(deviceId, DeviceConnectivityStatus.ONLINE)).thenReturn(true);

        handler.handle(topic, bytes("online"));

        verify(connectivityRegistry).applyObservedStatus(deviceId, DeviceConnectivityStatus.ONLINE);
        verify(realtimePublisher).publishDeviceConnectivity(deviceId, "ONLINE");
    }

    @Test
    void knownDevice_offlinePayload_persistsAndBroadcasts() {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/status";
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(connectivityRegistry.applyObservedStatus(deviceId, DeviceConnectivityStatus.OFFLINE)).thenReturn(true);

        handler.handle(topic, bytes("offline"));

        verify(connectivityRegistry).applyObservedStatus(deviceId, DeviceConnectivityStatus.OFFLINE);
        verify(realtimePublisher).publishDeviceConnectivity(deviceId, "OFFLINE");
    }

    // ---------- duplicate observation: harmless, no broadcast ----------

    @Test
    void duplicateOnline_registryReportsNoChange_doesNotBroadcast() {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/status";
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(connectivityRegistry.applyObservedStatus(deviceId, DeviceConnectivityStatus.ONLINE)).thenReturn(false);

        handler.handle(topic, bytes("online"));

        verify(realtimePublisher, never()).publishDeviceConnectivity(any(), any());
    }

    @Test
    void duplicateOffline_registryReportsNoChange_doesNotBroadcast() {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/status";
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(connectivityRegistry.applyObservedStatus(deviceId, DeviceConnectivityStatus.OFFLINE)).thenReturn(false);

        handler.handle(topic, bytes("offline"));

        verify(realtimePublisher, never()).publishDeviceConnectivity(any(), any());
    }

    // ---------- unknown device: dropped, no creation ----------

    @Test
    void unknownDevice_doesNotThrow_doesNotPersistOrBroadcast() {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/status";
        when(deviceRepository.existsById(deviceId)).thenReturn(false);

        assertDoesNotThrow(() -> handler.handle(topic, bytes("online")));

        verify(connectivityRegistry, never()).applyObservedStatus(any(), any());
        verify(realtimePublisher, never()).publishDeviceConnectivity(any(), any());
    }

    @Test
    void unknownDevice_neverCreatesADevice() {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/status";
        when(deviceRepository.existsById(deviceId)).thenReturn(false);

        handler.handle(topic, bytes("online"));

        verify(deviceRepository, never()).save(any());
    }

    // ---------- invalid payload: dropped ----------

    @Test
    void invalidPayload_doesNotThrow_doesNotTouchDeviceRepositoryOrRegistry() {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/status";

        assertDoesNotThrow(() -> handler.handle(topic, bytes("not-a-real-status")));

        verify(deviceRepository, never()).existsById(any());
        verify(connectivityRegistry, never()).applyObservedStatus(any(), any());
    }

    @Test
    void emptyPayload_doesNotThrow() {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/status";

        assertDoesNotThrow(() -> handler.handle(topic, bytes("")));
    }

    // ---------- invalid topic shape: dropped ----------

    @Test
    void malformedTopic_wrongSegmentCount_doesNotThrow() {
        assertDoesNotThrow(() -> handler.handle("nexus/devices/status", bytes("online")));
        verify(deviceRepository, never()).existsById(any());
    }

    @Test
    void malformedTopic_nonUuidDeviceSegment_doesNotThrow() {
        assertDoesNotThrow(() -> handler.handle("nexus/devices/not-a-uuid/status", bytes("online")));
        verify(deviceRepository, never()).existsById(any());
    }

    // ---------- transient failure: propagates, not swallowed ----------

    @Test
    void registryThrows_propagatesException() {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/status";
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(connectivityRegistry.applyObservedStatus(eq(deviceId), any()))
                .thenThrow(new RuntimeException("simulated DB failure"));

        assertThrows(RuntimeException.class, () -> handler.handle(topic, bytes("online")));
    }
}
