package com.nexus.action.application.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionExecutionResult;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.CapabilityType;
import com.nexus.device.capability.DeviceCapabilityRepository;
import com.nexus.device.connectivity.DeviceConnectivity;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.domain.DeviceRepository;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tier 5D: CommandExecutor is the sole gate that turns an ActionExecutionRequest
 * into a real, non-retained, QoS-1 MQTT command - only after device
 * existence, capability authorization, command-shape validation, and
 * device connectivity all pass, in that order.
 */
class CommandExecutorTest {

    private DeviceRepository deviceRepository;
    private DeviceCapabilityRepository deviceCapabilityRepository;
    private DeviceConnectivityRegistry deviceConnectivityRegistry;
    private MqttClient mqttClient;
    private CommandExecutor executor;

    private UUID deviceId;
    private ActionExecutionRequest ledOnRequest;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        deviceCapabilityRepository = mock(DeviceCapabilityRepository.class);
        deviceConnectivityRegistry = mock(DeviceConnectivityRegistry.class);
        mqttClient = mock(MqttClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        executor = new CommandExecutor(deviceRepository, deviceCapabilityRepository, deviceConnectivityRegistry, mqttClient, objectMapper);

        deviceId = UUID.randomUUID();
        ledOnRequest = new ActionExecutionRequest(
                UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "LED", "command", "SET_STATE", "value", true));
    }

    private void givenDeviceExistsAuthorizedAndOnline() throws Exception {
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(deviceCapabilityRepository.existsByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(true);
        when(deviceConnectivityRegistry.get(deviceId))
                .thenReturn(Optional.of(new DeviceConnectivity(deviceId, DeviceConnectivityStatus.ONLINE, Instant.now())));
        when(mqttClient.isConnected()).thenReturn(true);
    }

    // ---------- happy path ----------

    @Test
    void authorizedOnlineDevice_publishesCommand_andReturnsSuccess() throws Exception {
        givenDeviceExistsAuthorizedAndOnline();

        ActionExecutionResult result = executor.execute(ledOnRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        verify(mqttClient, times(1)).publish(anyString(), any(MqttMessage.class));
    }

    @Test
    void publishedCommand_usesCorrectTopic() throws Exception {
        givenDeviceExistsAuthorizedAndOnline();

        executor.execute(ledOnRequest, new ActionContext(null, null));

        org.mockito.ArgumentCaptor<String> topicCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mqttClient).publish(topicCaptor.capture(), any(MqttMessage.class));
        assertEquals("nexus/devices/" + deviceId + "/commands", topicCaptor.getValue());
    }

    @Test
    void publishedCommand_hasCorrectPayloadShape_andCommandIdEqualsRequestId() throws Exception {
        givenDeviceExistsAuthorizedAndOnline();

        executor.execute(ledOnRequest, new ActionContext(null, null));

        org.mockito.ArgumentCaptor<MqttMessage> messageCaptor = org.mockito.ArgumentCaptor.forClass(MqttMessage.class);
        verify(mqttClient).publish(anyString(), messageCaptor.capture());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode payload = mapper.readTree(messageCaptor.getValue().getPayload());
        assertEquals(ledOnRequest.requestId().toString(), payload.get("commandId").asText());
        assertEquals("LED", payload.get("capability").asText());
        assertEquals("SET_STATE", payload.get("command").asText());
        assertTrue(payload.get("value").asBoolean());
        assertTrue(payload.get("timestamp").isIntegralNumber(), "timestamp must be an epoch-seconds integer, not a string");
    }

    @Test
    void publishedCommand_usesQos1_andIsNotRetained() throws Exception {
        givenDeviceExistsAuthorizedAndOnline();

        executor.execute(ledOnRequest, new ActionContext(null, null));

        org.mockito.ArgumentCaptor<MqttMessage> messageCaptor = org.mockito.ArgumentCaptor.forClass(MqttMessage.class);
        verify(mqttClient).publish(anyString(), messageCaptor.capture());
        assertEquals(1, messageCaptor.getValue().getQos());
        assertFalse(messageCaptor.getValue().isRetained());
    }

    // ---------- capability authorization ----------

    @Test
    void missingWriteCapability_rejectsWithoutPublishing() throws Exception {
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(deviceCapabilityRepository.existsByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(false);

        ActionExecutionResult result = executor.execute(ledOnRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));
    }

    @Test
    void deviceHasReadOnly_notWrite_rejectsWithoutPublishing() throws Exception {
        // Device has LED READ but not LED WRITE - the exact "wrong direction" case.
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(deviceCapabilityRepository.existsByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.READ))
                .thenReturn(true);
        when(deviceCapabilityRepository.existsByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(false);

        ActionExecutionResult result = executor.execute(ledOnRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));
    }

    // ---------- unsupported command ----------

    @Test
    void unsupportedCommand_forAuthorizedCapability_rejectsWithoutPublishing() throws Exception {
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(deviceCapabilityRepository.existsByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(true);
        ActionExecutionRequest badCommand = new ActionExecutionRequest(
                UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "LED", "command", "BLINK", "value", true));

        ActionExecutionResult result = executor.execute(badCommand, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));
    }

    @Test
    void nonBooleanValue_rejectsWithoutPublishing() throws Exception {
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(deviceCapabilityRepository.existsByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(true);
        ActionExecutionRequest badValue = new ActionExecutionRequest(
                UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "LED", "command", "SET_STATE", "value", "not-a-boolean"));

        ActionExecutionResult result = executor.execute(badValue, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));
    }

    // ---------- connectivity precondition ----------

    @Test
    void offlineDevice_rejectsWithoutPublishing() throws Exception {
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(deviceCapabilityRepository.existsByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(true);
        when(deviceConnectivityRegistry.get(deviceId))
                .thenReturn(Optional.of(new DeviceConnectivity(deviceId, DeviceConnectivityStatus.OFFLINE, Instant.now())));

        ActionExecutionResult result = executor.execute(ledOnRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        assertTrue(result.message().toLowerCase().contains("offline"));
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));
    }

    @Test
    void unknownConnectivity_neverObserved_rejectsWithoutPublishing() throws Exception {
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(deviceCapabilityRepository.existsByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(true);
        when(deviceConnectivityRegistry.get(deviceId)).thenReturn(Optional.empty());

        ActionExecutionResult result = executor.execute(ledOnRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));
    }

    // ---------- MQTT-level preconditions/failures ----------

    @Test
    void mqttDisconnected_rejectsWithoutPublishing() throws Exception {
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        when(deviceCapabilityRepository.existsByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(true);
        when(deviceConnectivityRegistry.get(deviceId))
                .thenReturn(Optional.of(new DeviceConnectivity(deviceId, DeviceConnectivityStatus.ONLINE, Instant.now())));
        when(mqttClient.isConnected()).thenReturn(false);

        ActionExecutionResult result = executor.execute(ledOnRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));
    }

    @Test
    void publishThrows_returnsFailure_notFabricatedSuccess() throws Exception {
        givenDeviceExistsAuthorizedAndOnline();
        doThrow(new MqttException(32104)).when(mqttClient).publish(anyString(), any(MqttMessage.class));

        ActionExecutionResult result = executor.execute(ledOnRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
    }

    // ---------- unknown device ----------

    @Test
    void unknownDevice_rejectsWithoutPublishing() {
        when(deviceRepository.existsById(deviceId)).thenReturn(false);

        ActionExecutionResult result = executor.execute(ledOnRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        verifyNoInteractions(mqttClient);
    }

    // ---------- null ActionContext tolerance ----------

    @Test
    void nullActionContext_doesNotThrow() throws Exception {
        givenDeviceExistsAuthorizedAndOnline();

        assertDoesNotThrow(() -> executor.execute(ledOnRequest, new ActionContext(null, null)));
    }
}
