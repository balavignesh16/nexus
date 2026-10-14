package com.nexus.rule.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Phase 3 executable configuration carried by an immutable RuleVersion: explicit trigger subscriptions,
 * repeat policy and optional schedule. Absent (null) on every imported/legacy definition, which keeps the
 * legacy adapter behavior and its stored JSON byte-stable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleRuntime(List<TriggerSubscription> triggers, RepeatPolicy repeatPolicy, ScheduleSpec schedule) {
    public RuleRuntime {
        triggers=triggers==null?List.of():List.copyOf(triggers);
    }
}
