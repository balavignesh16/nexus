package com.nexus.action.application;

import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutedEvent;
import com.nexus.action.domain.ActionExecutionFailedEvent;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionExecutionResult;
import com.nexus.action.domain.ExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class ActionExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutionEngine.class);

    private final ActionExecutorRegistry registry;
    private final ApplicationEventPublisher eventPublisher;

    public ActionExecutionEngine(ActionExecutorRegistry registry, ApplicationEventPublisher eventPublisher) {
        this.registry = registry;
        this.eventPublisher = eventPublisher;
    }

    public void process(List<ActionExecutionRequest> requests, ActionContext context, String correlationId) {
        for (ActionExecutionRequest request : requests) {
            ActionExecutionResult result = executeSingleRequest(request, context);
            
            if (result.status() == ExecutionStatus.FAILURE) {
                eventPublisher.publishEvent(new ActionExecutionFailedEvent(result, correlationId));
            } else {
                eventPublisher.publishEvent(new ActionExecutedEvent(result, correlationId));
            }
        }
    }

    private ActionExecutionResult executeSingleRequest(ActionExecutionRequest request, ActionContext context) {
        Optional<ActionExecutor> executorOpt = registry.resolve(request.actionType());
        
        if (executorOpt.isEmpty()) {
            log.warn("No executor found for ActionType: {}", request.actionType());
            return new ActionExecutionResult(
                    request.requestId(),
                    request,
                    ExecutionStatus.UNSUPPORTED,
                    Instant.now(),
                    "No executor found for action type: " + request.actionType()
            );
        }

        try {
            return executorOpt.get().execute(request, context);
        } catch (Exception e) {
            log.error("Execution failed for request {}", request.requestId(), e);
            return new ActionExecutionResult(
                    request.requestId(),
                    request,
                    ExecutionStatus.FAILURE,
                    Instant.now(),
                    e.getMessage()
            );
        }
    }
}
