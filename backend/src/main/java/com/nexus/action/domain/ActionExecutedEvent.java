package com.nexus.action.domain;

public record ActionExecutedEvent(
    ActionExecutionResult result,
    String correlationId
) {}
