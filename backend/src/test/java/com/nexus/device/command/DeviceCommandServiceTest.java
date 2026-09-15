package com.nexus.device.command;

import com.nexus.action.api.dto.ActionHistoryResponse;
import com.nexus.action.application.ActionExecutionEngine;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.device.capability.CapabilityType;
import com.nexus.device.command.api.dto.CreateDeviceCommandRequest;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.exception.DeviceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeviceCommandServiceTest {

    private DeviceRepository deviceRepository;
    private ActionExecutionEngine actionExecutionEngine;
    private ActionHistoryRepository actionHistoryRepository;
    private DeviceCommandService service;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        actionExecutionEngine = mock(ActionExecutionEngine.class);
        actionHistoryRepository = mock(ActionHistoryRepository.class);
        service = new DeviceCommandService(deviceRepository, actionExecutionEngine, actionHistoryRepository);
    }

    @Test
    void unknownDevice_throwsDeviceNotFoundException_neverInvokesEngine() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.existsById(deviceId)).thenReturn(false);
        CreateDeviceCommandRequest request = new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true);

        assertThatThrownBy(() -> service.dispatchCommand(deviceId, request))
                .isInstanceOf(DeviceNotFoundException.class);
        verifyNoInteractions(actionExecutionEngine);
    }

    @Test
    void validCommand_invokesActionExecutionEngine_withMatchIdNull_andNullContext() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        CreateDeviceCommandRequest request = new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true);

        // Simulate the engine's synchronous side effect: the history record
        // exists by the time process() returns.
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.List<ActionExecutionRequest> requests = invocation.getArgument(0);
            ActionExecutionRequest actionRequest = requests.get(0);
            when(actionHistoryRepository.findById(actionRequest.requestId())).thenReturn(Optional.of(
                    new ActionHistoryRecord(actionRequest.requestId(), null, deviceId, ActionType.SEND_COMMAND,
                            ExecutionStatus.SUCCESS, actionRequest.parameters(), Instant.now(), "Command published to MQTT")));
            return null;
        }).when(actionExecutionEngine).process(any(), any(), any());

        service.dispatchCommand(deviceId, request);

        org.mockito.ArgumentCaptor<java.util.List<ActionExecutionRequest>> requestsCaptor = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        org.mockito.ArgumentCaptor<ActionContext> contextCaptor = org.mockito.ArgumentCaptor.forClass(ActionContext.class);
        verify(actionExecutionEngine).process(requestsCaptor.capture(), contextCaptor.capture(), any());

        ActionExecutionRequest actionRequest = requestsCaptor.getValue().get(0);
        assertThat(actionRequest.matchId()).isNull();
        assertThat(actionRequest.actionType()).isEqualTo(ActionType.SEND_COMMAND);
        assertThat(actionRequest.parameters()).containsEntry("deviceId", deviceId.toString());
        assertThat(actionRequest.parameters()).containsEntry("capability", "LED");
        assertThat(actionRequest.parameters()).containsEntry("command", "SET_STATE");
        assertThat(actionRequest.parameters()).containsEntry("value", true);

        assertThat(contextCaptor.getValue().triggeringEvent()).isNull();
        assertThat(contextCaptor.getValue().matchedRule()).isNull();
    }

    @Test
    void validCommand_returnsResponseBuiltFromPersistedHistoryRecord() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        CreateDeviceCommandRequest request = new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", false);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.List<ActionExecutionRequest> requests = invocation.getArgument(0);
            ActionExecutionRequest actionRequest = requests.get(0);
            when(actionHistoryRepository.findById(actionRequest.requestId())).thenReturn(Optional.of(
                    new ActionHistoryRecord(actionRequest.requestId(), null, deviceId, ActionType.SEND_COMMAND,
                            ExecutionStatus.FAILURE, actionRequest.parameters(), Instant.now(), "Device is offline")));
            return null;
        }).when(actionExecutionEngine).process(any(), any(), any());

        ActionHistoryResponse response = service.dispatchCommand(deviceId, request);

        assertThat(response.status()).isEqualTo(ExecutionStatus.FAILURE);
        assertThat(response.message()).isEqualTo("Device is offline");
        assertThat(response.matchId()).isNull();
    }

    // ---------- Phase 13: widened value type (Object, not just Boolean) ----------

    @Test
    void relayValue_structuredMap_passesThroughUnchangedToActionParameters() {
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.existsById(deviceId)).thenReturn(true);
        java.util.Map<String, Object> relayValue = java.util.Map.of("channel", 2, "state", true);
        CreateDeviceCommandRequest request = new CreateDeviceCommandRequest(CapabilityType.RELAY, "SET_STATE", relayValue);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.List<ActionExecutionRequest> requests = invocation.getArgument(0);
            ActionExecutionRequest actionRequest = requests.get(0);
            when(actionHistoryRepository.findById(actionRequest.requestId())).thenReturn(Optional.of(
                    new ActionHistoryRecord(actionRequest.requestId(), null, deviceId, ActionType.SEND_COMMAND,
                            ExecutionStatus.SUCCESS, actionRequest.parameters(), Instant.now(), "Command published to MQTT")));
            return null;
        }).when(actionExecutionEngine).process(any(), any(), any());

        service.dispatchCommand(deviceId, request);

        org.mockito.ArgumentCaptor<java.util.List<ActionExecutionRequest>> requestsCaptor = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(actionExecutionEngine).process(requestsCaptor.capture(), any(), any());
        assertThat(requestsCaptor.getValue().get(0).parameters()).containsEntry("value", relayValue);
    }
}
