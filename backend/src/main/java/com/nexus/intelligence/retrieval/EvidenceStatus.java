package com.nexus.intelligence.retrieval;

/**
 * What a lookup established. OBSERVED: persisted facts were read. DERIVED: computed deterministically from persisted facts (anomalies, recommendations).
 * INSUFFICIENT_EVIDENCE: the platform looked and what it holds cannot support a statement (no row, too few rows, an ambiguous identifier). NOT_FOUND: the named
 * entity does not exist. NOT_AVAILABLE: the lookup could not be completed (a limit, a timeout, a busy service, a disabled capability). NOT_AUTHORIZED is reserved
 * for a real access-control boundary; NEXUS has no user authentication, so no lookup produces it today.
 */
public enum EvidenceStatus {
    OBSERVED, DERIVED, INSUFFICIENT_EVIDENCE, NOT_FOUND, NOT_AVAILABLE, NOT_AUTHORIZED
}
