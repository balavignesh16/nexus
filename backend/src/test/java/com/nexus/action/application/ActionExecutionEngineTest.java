package com.nexus.action.application;

import com.nexus.action.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import com.nexus.realtime.RealtimePublisher;
import com.nexus.pipeline.application.PipelineService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ActionExecutionEngineTest {

    private ActionExecutorRegistry registry;
    private ApplicationEventPublisher eventPublisher;
    private RealtimePublisher realtimePublisher;
    private ActionExecutionEngine engine;

    @BeforeEach
    void setUp() {
        registry = mock(ActionExecutorRegistry.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        realtimePublisher = mock(RealtimePublisher.class);
        PipelineService pipelineService = mock(PipelineService.class);
        engine = new ActionExecutionEngine(registry, eventPublisher, realtimePublisher, pipelineService);
    }

    @Test
    void shouldExecuteActionAndPublishEventWhenExecutorFound() {
        ActionExecutionRequest request = new ActionExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), ActionType.CREATE_ALERT, Map.of()
        );
        ActionContext context = new ActionContext(null, null);

        ActionExecutor executor = mock(ActionExecutor.class);
        ActionExecutionResult mockResult = new ActionExecutionResult(
                UUID.randomUUID(), request, ExecutionStatus.SUCCESS, java.time.Instant.now(), "Success"
        );
        when(registry.resolve(ActionType.CREATE_ALERT)).thenReturn(Optional.of(executor));
        when(executor.execute(request, context)).thenReturn(mockResult);

        engine.process(List.of(request), context, "corr-123");

        verify(executor).execute(request, context);

        ArgumentCaptor<ActionExecutedEvent> captor = ArgumentCaptor.forClass(ActionExecutedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        ActionExecutedEvent event = captor.getValue();
        assertEquals(ExecutionStatus.SUCCESS, event.result().status());
        assertEquals("corr-123", event.correlationId());
    }

    @Test
    void shouldPublishUnsupportedWhenExecutorNotFound() {
        ActionExecutionRequest request = new ActionExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), ActionType.WEBHOOK, Map.of()
        );
        ActionContext context = new ActionContext(null, null);

        when(registry.resolve(ActionType.WEBHOOK)).thenReturn(Optional.empty());

        engine.process(List.of(request), context, "corr-456");

        ArgumentCaptor<ActionExecutedEvent> captor = ArgumentCaptor.forClass(ActionExecutedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        ActionExecutedEvent event = captor.getValue();
        assertEquals(ExecutionStatus.UNSUPPORTED, event.result().status());
        assertTrue(event.result().message().contains("No executor found"));
    }

    @Test
    void shouldPublishFailedEventWhenExecutorThrowsException() {
        ActionExecutionRequest request = new ActionExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), ActionType.SEND_COMMAND, Map.of()
        );
        ActionContext context = new ActionContext(null, null);

        ActionExecutor executor = mock(ActionExecutor.class);
        when(registry.resolve(ActionType.SEND_COMMAND)).thenReturn(Optional.of(executor));
        when(executor.execute(request, context)).thenThrow(new RuntimeException("Simulated error"));

        engine.process(List.of(request), context, "corr-789");

        ArgumentCaptor<ActionExecutionFailedEvent> captor = ArgumentCaptor.forClass(ActionExecutionFailedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        ActionExecutionFailedEvent event = captor.getValue();
        assertEquals(ExecutionStatus.FAILURE, event.result().status());
        assertEquals("Simulated error", event.result().message());
    }

    @Test
    void shouldPersistActionHistoryBeforeBroadcastingRealtimeMessage() {
        ActionExecutionRequest request = new ActionExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), ActionType.CREATE_ALERT, Map.of()
        );
        ActionContext context = new ActionContext(null, null);

        ActionExecutor executor = mock(ActionExecutor.class);
        ActionExecutionResult mockResult = new ActionExecutionResult(
                UUID.randomUUID(), request, ExecutionStatus.SUCCESS, java.time.Instant.now(), "Success"
        );
        when(registry.resolve(ActionType.CREATE_ALERT)).thenReturn(Optional.of(executor));
        when(executor.execute(request, context)).thenReturn(mockResult);

        engine.process(List.of(request), context, "corr-order-1");

        // eventPublisher.publishEvent(...) synchronously drives ActionHistoryPersister's
        // repository.save(...) before control returns here, so asserting it happens
        // before the realtime broadcast is equivalent to asserting persist-before-broadcast.
        InOrder inOrder = inOrder(eventPublisher, realtimePublisher);
        inOrder.verify(eventPublisher).publishEvent(any(ActionExecutedEvent.class));
        inOrder.verify(realtimePublisher).publishActionExecuted(eq(mockResult), eq("corr-order-1"));
    }
}
