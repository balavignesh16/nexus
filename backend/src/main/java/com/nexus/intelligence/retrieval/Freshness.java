package com.nexus.intelligence.retrieval;

/** Age of the newest evidence on the BACKEND clock. FUTURE_DATED means the device stamped it later than the backend allows (clock skew), so its age is unknown. */
public enum Freshness {
    FRESH, STALE, FUTURE_DATED, UNKNOWN
}
