package com.nexus.action.application;

import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.action.domain.*;
import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.twin.application.VerificationService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommandDispatchCoordinatorTest {
    private static final String CORRELATION_ID="corr-command";
    private final CommandAcceptanceService acceptance = mock(CommandAcceptanceService.class);
    private final CommandExecutor executor = mock(CommandExecutor.class);
    private final ActionHistoryRepository actions = mock(ActionHistoryRepository.class);
    private final CommandDispatchCoordinator coordinator = new CommandDispatchCoordinator(acceptance, executor, actions);
    private final UUID deviceId = UUID.randomUUID();
    private final ActionExecutionRequest request = new ActionExecutionRequest(UUID.randomUUID(), null,
            ActionType.SEND_COMMAND, Map.of("deviceId", deviceId.toString(), "capability", "LED",
                    "command", "SET_STATE", "value", true));
    private final ActionContext context = new ActionContext(null, null);
    private final CommandExecutor.PreparedCommand prepared = new CommandExecutor.PreparedCommand(deviceId,
            UUID.randomUUID(), null, UUID.randomUUID(), "led", CapabilityValueType.BOOLEAN,
            null, "", "LED", "SET_STATE", true);

    @Test void durableAcceptancePrecedesPublishAndSuccessUpdatesOnlyItsAction() {
        when(acceptance.accept(eq(request), eq(context), any(),eq(CORRELATION_ID))).thenReturn(prepared);
        ActionExecutionResult result = new ActionExecutionResult(request.requestId(), request,
                ExecutionStatus.SUCCESS, Instant.now(), "published");
        when(executor.publishPrepared(request, prepared)).thenReturn(result);
        assertTrue(coordinator.dispatch(request, context,CORRELATION_ID).accepted());
        InOrder order = inOrder(acceptance, executor, actions);
        order.verify(acceptance).accept(eq(request), eq(context), any(),eq(CORRELATION_ID));
        order.verify(executor).publishPrepared(request, prepared);
        order.verify(actions).updatePublishResultIfAccepted(eq(request.requestId()), eq(ExecutionStatus.SUCCESS),
                any(), any(), eq(result.message()));
    }

    @Test void publishFailureStillHasAcceptedIntent() {
        var reconciliation=mock(VerificationService.class);
        coordinator.setVerification(reconciliation);
        when(acceptance.accept(eq(request), eq(context), any(),eq(CORRELATION_ID))).thenReturn(prepared);
        when(executor.publishPrepared(request, prepared)).thenReturn(new ActionExecutionResult(
                request.requestId(), request, ExecutionStatus.FAILURE, Instant.now(), "MQTT failed"));
        var outcome = coordinator.dispatch(request, context,CORRELATION_ID);
        assertTrue(outcome.accepted());
        assertEquals(ExecutionStatus.FAILURE, outcome.result().status());
        verify(actions).updatePublishResultIfAccepted(eq(request.requestId()), eq(ExecutionStatus.FAILURE), any(), isNull(), any());
        verify(reconciliation).commandFailed(deviceId,request.requestId(),"MQTT_PUBLICATION_FAILED");
    }

    @Test void rejectedRequestCannotPublishOrBecomeAccepted() {
        when(acceptance.accept(eq(request), eq(context), any(),eq(CORRELATION_ID)))
                .thenThrow(new CommandExecutor.CommandRejectedException("invalid"));
        var outcome = coordinator.dispatch(request, context,CORRELATION_ID);
        assertFalse(outcome.accepted());
        assertEquals(ExecutionStatus.FAILURE, outcome.result().status());
        verifyNoInteractions(executor, actions);
    }

    @Test void acceptanceTransactionFailureCannotPublishOrUpdatePublishResult() {
        when(acceptance.accept(eq(request), eq(context), any(),eq(CORRELATION_ID)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("desired write failed"));

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> coordinator.dispatch(request, context,CORRELATION_ID));
        verifyNoInteractions(executor, actions);
    }
}
