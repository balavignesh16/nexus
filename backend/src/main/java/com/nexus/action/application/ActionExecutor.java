package com.nexus.action.application;

import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionExecutionResult;
import com.nexus.action.domain.ActionType;

public interface ActionExecutor {
    boolean supports(ActionType type);
    ActionExecutionResult execute(ActionExecutionRequest request, ActionContext context);
}
