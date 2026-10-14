package com.nexus.rule.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Explicit subscription inside one immutable version. Filters narrow a trigger family; supported keys:
 * capabilityKey (telemetry/twin), status (VERIFICATION_CHANGED), connectivity (CONNECTIVITY_CHANGED),
 * parentRuleId (RULE_MATCHED). Imported rules carry no subscriptions and are treated as LEGACY_DOMAIN_EVENT only.
 */
public record TriggerSubscription(TriggerType type, Map<String,String> filters) {
    public TriggerSubscription {
        filters=filters==null?Map.of():Collections.unmodifiableMap(new LinkedHashMap<>(filters));
    }
    public TriggerSubscription(TriggerType type){this(type,Map.of());}
}
