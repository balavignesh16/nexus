package com.nexus.action.application.executor;

import com.nexus.action.application.ActionExecutor;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionExecutionResult;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class CreateAlertExecutor implements ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(CreateAlertExecutor.class);

    @Override
    public boolean supports(ActionType type) {
        return type == ActionType.CREATE_ALERT;
    }

    @Override
    public ActionExecutionResult execute(ActionExecutionRequest request, ActionContext context) {
        // Stub implementation
        log.info("Executing CREATE_ALERT for device: {}. Parameters: {}", 
                context.triggeringEvent().deviceId(), request.parameters());
        
        return new ActionExecutionResult(
                UUID.randomUUID(),
                request,
                ExecutionStatus.SUCCESS,
                Instant.now(),
                "Alert created successfully (stub)"
        );
    }
}
