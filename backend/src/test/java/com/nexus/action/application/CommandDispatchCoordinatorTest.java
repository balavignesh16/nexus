package com.nexus.action.application;

import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.action.domain.*;
import com.nexus.device.capability.definition.CapabilityValueType;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommandDispatchCoordinatorTest {
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
        when(acceptance.accept(eq(request), eq(context), any())).thenReturn(prepared);
        ActionExecutionResult result = new ActionExecutionResult(request.requestId(), request,
                ExecutionStatus.SUCCESS, Instant.now(), "published");
        when(executor.publishPrepared(request, prepared)).thenReturn(result);
        assertTrue(coordinator.dispatch(request, context).accepted());
        InOrder order = inOrder(acceptance, executor, actions);
        order.verify(acceptance).accept(eq(request), eq(context), any());
        order.verify(executor).publishPrepared(request, prepared);
        order.verify(actions).updatePublishResultIfAccepted(request.requestId(), ExecutionStatus.SUCCESS,
                result.timestamp(), result.message());
    }

    @Test void publishFailureStillHasAcceptedIntent() {
        when(acceptance.accept(eq(request), eq(context), any())).thenReturn(prepared);
        when(executor.publishPrepared(request, prepared)).thenReturn(new ActionExecutionResult(
                request.requestId(), request, ExecutionStatus.FAILURE, Instant.now(), "MQTT failed"));
        var outcome = coordinator.dispatch(request, context);
        assertTrue(outcome.accepted());
        assertEquals(ExecutionStatus.FAILURE, outcome.result().status());
        verify(actions).updatePublishResultIfAccepted(eq(request.requestId()), eq(ExecutionStatus.FAILURE), any(), any());
    }

    @Test void rejectedRequestCannotPublishOrBecomeAccepted() {
        when(acceptance.accept(eq(request), eq(context), any()))
                .thenThrow(new CommandExecutor.CommandRejectedException("invalid"));
        var outcome = coordinator.dispatch(request, context);
        assertFalse(outcome.accepted());
        assertEquals(ExecutionStatus.FAILURE, outcome.result().status());
        verifyNoInteractions(executor, actions);
    }

    @Test void acceptanceTransactionFailureCannotPublishOrUpdatePublishResult() {
        when(acceptance.accept(eq(request), eq(context), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("desired write failed"));

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> coordinator.dispatch(request, context));
        verifyNoInteractions(executor, actions);
    }
}
