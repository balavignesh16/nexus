package com.nexus.rule.ai;

import java.util.UUID;

/** The immutable rule version a match was evaluated at, exactly as persisted. Absent (null) for historical matches that predate attribution - never inferred. */
public record VersionAttribution(UUID versionId, Long revision, Long executionEpoch) {}
