package com.nexus.action.domain;

import com.nexus.event.domain.DomainEvent;
import com.nexus.rule.domain.Rule;

public record ActionContext(
    DomainEvent triggeringEvent,
    Rule matchedRule
) {}
