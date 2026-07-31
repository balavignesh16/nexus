package com.nexus.action.domain;

import java.util.Map;
import java.util.UUID;

public record ActionExecutionRequest(
    UUID requestId,
    UUID matchId,
    ActionType actionType,
    Map<String, Object> parameters
) {}
