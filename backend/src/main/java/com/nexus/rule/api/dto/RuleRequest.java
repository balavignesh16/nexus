package com.nexus.rule.api.dto;

import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

public record RuleRequest(
    @NotBlank String name,
    boolean enabled,
    int priority,
    @NotEmpty List<RuleCondition> conditions,
    @NotEmpty List<RuleAction> actions,
    Map<String, String> metadata
) {}
