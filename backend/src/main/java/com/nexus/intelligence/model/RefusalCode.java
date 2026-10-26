package com.nexus.intelligence.model;

/** Why the gateway produced no model output. Closed set: also the metric tag of the outcome. */
public enum RefusalCode {
    DISABLED, NO_CONSENT, CONTEXT_TOO_LARGE, CIRCUIT_OPEN, BULKHEAD_FULL, TIMEOUT, PROVIDER_ERROR, OUTPUT_TOO_LARGE, OUTPUT_INVALID
}
