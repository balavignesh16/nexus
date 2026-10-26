package com.nexus.intelligence.retrieval;

/** The closed set of lookups the assistant can ask for. Each one is a fixed, parameterised, read-only query or an existing read service; there is no generic query. */
public enum LookupType {
    DEVICE_STATE, TWIN_STATE, LATEST_TELEMETRY, TELEMETRY_HISTORY, CONNECTIVITY, ACTIVE_RULES, RULE_MATCHES, COMMAND_HISTORY, DELIVERY_EVIDENCE, VERIFICATION, ANOMALIES, RECOMMENDATIONS
}
