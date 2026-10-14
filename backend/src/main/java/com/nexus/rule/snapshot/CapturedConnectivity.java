package com.nexus.rule.snapshot;

import java.time.Instant;

/** Persisted connectivity row with its transition generation; absence of a row is UNKNOWN, never OFFLINE. */
public record CapturedConnectivity(String status,Instant lastChangedAt,long generation) {}
