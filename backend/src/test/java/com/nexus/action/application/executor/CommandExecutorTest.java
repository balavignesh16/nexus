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
import com.nexus.device.capability.DeviceCapability;
import com.nexus.device.capability.DeviceCapabilityRepository;
import com.nexus.device.connectivity.DeviceConnectivity;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tier 5D / Phase 13: CommandExecutor is the sole gate that turns an
 * ActionExecutionRequest into a real, non-retained, QoS-1 MQTT command -
 * only after device existence, REAL-mode rejection, capability
 * authorization, command/value-shape validation (CommandValueValidator),
 * and device connectivity all pass, in that order.
 */
class CommandExecutorTest {
    @Test
    void explicitUsbRealOptInStillRequiresWriteCapabilityAndConnectivity() throws Exception {
        givenDeviceExistsAuthorizedAndOnline();
        when(simulatedDevice.getMode()).thenReturn(DeviceMode.REAL);
        when(simulatedDevice.getStatus()).thenReturn(com.nexus.device.domain.DeviceStatus.ACTIVE);
        executor.setUsbRealCommandPolicy(new UsbRealCommandPolicy(deviceId.toString(),""));
        assertEquals(ExecutionStatus.SUCCESS,executor.execute(ledOnRequest,new ActionContext(null,null)).status());
        clearInvocations(mqttClient);
        when(deviceCapabilityRepository.findByDeviceIdAndCapabilityTypeAndDirection(deviceId,CapabilityType.LED,CapabilityDirection.WRITE)).thenReturn(Optional.empty());
        assertEquals(ExecutionStatus.FAILURE,executor.execute(ledOnRequest,new ActionContext(null,null)).status());
        verify(mqttClient,never()).publish(anyString(),any(MqttMessage.class));
        givenDeviceExistsAuthorizedAndOnline();
        when(deviceConnectivityRegistry.get(deviceId)).thenReturn(Optional.empty());
        assertEquals(ExecutionStatus.FAILURE,executor.execute(ledOnRequest,new ActionContext(null,null)).status());
        verify(mqttClient,never()).publish(anyString(),any(MqttMessage.class));
    }

    private DeviceRepository deviceRepository;
    private DeviceCapabilityRepository deviceCapabilityRepository;
    private DeviceConnectivityRegistry deviceConnectivityRegistry;
    private MqttClient mqttClient;
    private CommandExecutor executor;

    private UUID deviceId;
    private Device simulatedDevice;
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
        simulatedDevice = mock(Device.class);
        when(simulatedDevice.getMode()).thenReturn(DeviceMode.SIMULATED);

        ledOnRequest = new ActionExecutionRequest(
                UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "LED", "command", "SET_STATE", "value", true));
    }

    private DeviceCapability writeCapability(CapabilityType type, Map<String, Object> metadata) {
        DeviceCapability capability = mock(DeviceCapability.class);
        when(capability.getMetadata()).thenReturn(metadata);
        return capability;
    }

    private void givenDeviceExistsAuthorizedAndOnline() throws Exception {
        DeviceCapability ledCapability = writeCapability(CapabilityType.LED, Map.of());
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(simulatedDevice));
        when(deviceCapabilityRepository.findByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(Optional.of(ledCapability));
        when(deviceConnectivityRegistry.get(deviceId))
                .thenReturn(Optional.of(new DeviceConnectivity(deviceId, DeviceConnectivityStatus.ONLINE, Instant.now())));
        when(mqttClient.isConnected()).thenReturn(true);
    }

    // ---------- happy path (LED regression - byte-for-byte, pre-Phase-13 behavior) ----------

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
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(simulatedDevice));
        when(deviceCapabilityRepository.findByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(Optional.empty());

        ActionExecutionResult result = executor.execute(ledOnRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));
    }

    @Test
    void deviceHasReadOnly_notWrite_rejectsWithoutPublishing() throws Exception {
        // Device has LED READ but not LED WRITE - the exact "wrong direction" case.
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(simulatedDevice));
        when(deviceCapabilityRepository.findByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(Optional.empty());

        ActionExecutionResult result = executor.execute(ledOnRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));
    }

    // ---------- unsupported command ----------

    @Test
    void unsupportedCommand_forAuthorizedCapability_rejectsWithoutPublishing() throws Exception {
        DeviceCapability ledCapability = writeCapability(CapabilityType.LED, Map.of());
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(simulatedDevice));
        when(deviceCapabilityRepository.findByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(Optional.of(ledCapability));
        ActionExecutionRequest badCommand = new ActionExecutionRequest(
                UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "LED", "command", "BLINK", "value", true));

        ActionExecutionResult result = executor.execute(badCommand, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));
    }

    @Test
    void nonBooleanValue_rejectsWithoutPublishing() throws Exception {
        DeviceCapability ledCapability = writeCapability(CapabilityType.LED, Map.of());
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(simulatedDevice));
        when(deviceCapabilityRepository.findByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(Optional.of(ledCapability));
        ActionExecutionRequest badValue = new ActionExecutionRequest(
                UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "LED", "command", "SET_STATE", "value", "not-a-boolean"));

        ActionExecutionResult result = executor.execute(badValue, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));
    }

    // ---------- Phase 13: non-LED WRITE capabilities ----------

    @Test
    void relayCommand_validChannel_publishesCommand() throws Exception {
        DeviceCapability relayCapability = writeCapability(CapabilityType.RELAY, Map.of("channels", List.of(1, 2, 3, 4)));
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(simulatedDevice));
        when(deviceCapabilityRepository.findByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.RELAY, CapabilityDirection.WRITE))
                .thenReturn(Optional.of(relayCapability));
        when(deviceConnectivityRegistry.get(deviceId))
                .thenReturn(Optional.of(new DeviceConnectivity(deviceId, DeviceConnectivityStatus.ONLINE, Instant.now())));
        when(mqttClient.isConnected()).thenReturn(true);

        ActionExecutionRequest relayRequest = new ActionExecutionRequest(
                UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "RELAY", "command", "SET_STATE",
                        "value", Map.of("channel", 2, "state", true)));

        ActionExecutionResult result = executor.execute(relayRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        org.mockito.ArgumentCaptor<MqttMessage> messageCaptor = org.mockito.ArgumentCaptor.forClass(MqttMessage.class);
        verify(mqttClient).publish(anyString(), messageCaptor.capture());
        JsonNode payload = new ObjectMapper().readTree(messageCaptor.getValue().getPayload());
        assertEquals(2, payload.get("value").get("channel").asInt());
        assertTrue(payload.get("value").get("state").asBoolean());
    }

    @Test
    void relayCommand_invalidChannel_rejectsWithoutPublishing() throws Exception {
        DeviceCapability relayCapability = writeCapability(CapabilityType.RELAY, Map.of("channels", List.of(1, 2, 3, 4)));
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(simulatedDevice));
        when(deviceCapabilityRepository.findByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.RELAY, CapabilityDirection.WRITE))
                .thenReturn(Optional.of(relayCapability));

        ActionExecutionRequest relayRequest = new ActionExecutionRequest(
                UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "RELAY", "command", "SET_STATE",
                        "value", Map.of("channel", 9, "state", true)));

        ActionExecutionResult result = executor.execute(relayRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));
    }

    @Test
    void servoCommand_validAngle_publishesCommand() throws Exception {
        DeviceCapability servoCapability = writeCapability(CapabilityType.SERVO, Map.of());
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(simulatedDevice));
        when(deviceCapabilityRepository.findByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.SERVO, CapabilityDirection.WRITE))
                .thenReturn(Optional.of(servoCapability));
        when(deviceConnectivityRegistry.get(deviceId))
                .thenReturn(Optional.of(new DeviceConnectivity(deviceId, DeviceConnectivityStatus.ONLINE, Instant.now())));
        when(mqttClient.isConnected()).thenReturn(true);

        ActionExecutionRequest servoRequest = new ActionExecutionRequest(
                UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "SERVO", "command", "SET_STATE", "value", 90));

        ActionExecutionResult result = executor.execute(servoRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.SUCCESS, result.status());
    }

    @Test
    void servoCommand_outOfRangeAngle_rejectsWithoutPublishing() throws Exception {
        DeviceCapability servoCapability = writeCapability(CapabilityType.SERVO, Map.of());
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(simulatedDevice));
        when(deviceCapabilityRepository.findByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.SERVO, CapabilityDirection.WRITE))
                .thenReturn(Optional.of(servoCapability));

        ActionExecutionRequest servoRequest = new ActionExecutionRequest(
                UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "SERVO", "command", "SET_STATE", "value", 270));

        ActionExecutionResult result = executor.execute(servoRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));
    }

    @Test
    void fanAndBuzzerCommands_validBoolean_publishCommand() throws Exception {
        for (CapabilityType type : List.of(CapabilityType.FAN, CapabilityType.BUZZER)) {
            reset(deviceRepository, deviceCapabilityRepository, deviceConnectivityRegistry, mqttClient);
            DeviceCapability capability = writeCapability(type, Map.of());
            when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(simulatedDevice));
            when(deviceCapabilityRepository.findByDeviceIdAndCapabilityTypeAndDirection(deviceId, type, CapabilityDirection.WRITE))
                    .thenReturn(Optional.of(capability));
            when(deviceConnectivityRegistry.get(deviceId))
                    .thenReturn(Optional.of(new DeviceConnectivity(deviceId, DeviceConnectivityStatus.ONLINE, Instant.now())));
            when(mqttClient.isConnected()).thenReturn(true);

            ActionExecutionRequest request = new ActionExecutionRequest(
                    UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                    Map.of("deviceId", deviceId.toString(), "capability", type.name(), "command", "SET_STATE", "value", true));

            ActionExecutionResult result = executor.execute(request, new ActionContext(null, null));

            assertEquals(ExecutionStatus.SUCCESS, result.status(), type + " should be dispatchable with a boolean value");
        }
    }

    // ---------- Phase 13: REAL-mode guard ----------

    @Test
    void realDevice_rejectsCommandDispatch_regardlessOfOtherPreconditions() throws Exception {
        Device realDevice = mock(Device.class);
        when(realDevice.getMode()).thenReturn(DeviceMode.REAL);
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(realDevice));

        ActionExecutionResult result = executor.execute(ledOnRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        assertTrue(result.message().toLowerCase().contains("real"), "message must explain the REAL-mode rejection");
        verifyNoInteractions(mqttClient);
        // Must fail before ever touching capability/connectivity lookups.
        verifyNoInteractions(deviceCapabilityRepository);
    }

    // ---------- connectivity precondition ----------

    @Test
    void offlineDevice_rejectsWithoutPublishing() throws Exception {
        DeviceCapability ledCapability = writeCapability(CapabilityType.LED, Map.of());
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(simulatedDevice));
        when(deviceCapabilityRepository.findByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(Optional.of(ledCapability));
        when(deviceConnectivityRegistry.get(deviceId))
                .thenReturn(Optional.of(new DeviceConnectivity(deviceId, DeviceConnectivityStatus.OFFLINE, Instant.now())));

        ActionExecutionResult result = executor.execute(ledOnRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        assertTrue(result.message().toLowerCase().contains("offline"));
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));
    }

    @Test
    void unknownConnectivity_neverObserved_rejectsWithoutPublishing() throws Exception {
        DeviceCapability ledCapability = writeCapability(CapabilityType.LED, Map.of());
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(simulatedDevice));
        when(deviceCapabilityRepository.findByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(Optional.of(ledCapability));
        when(deviceConnectivityRegistry.get(deviceId)).thenReturn(Optional.empty());

        ActionExecutionResult result = executor.execute(ledOnRequest, new ActionContext(null, null));

        assertEquals(ExecutionStatus.FAILURE, result.status());
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));
    }

    // ---------- MQTT-level preconditions/failures ----------

    @Test
    void mqttDisconnected_rejectsWithoutPublishing() throws Exception {
        DeviceCapability ledCapability = writeCapability(CapabilityType.LED, Map.of());
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(simulatedDevice));
        when(deviceCapabilityRepository.findByDeviceIdAndCapabilityTypeAndDirection(deviceId, CapabilityType.LED, CapabilityDirection.WRITE))
                .thenReturn(Optional.of(ledCapability));
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
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.empty());

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
