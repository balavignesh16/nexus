package com.nexus.action.domain;

public record ActionExecutionFailedEvent(
    ActionExecutionResult result,
    String correlationId
) {}
