package com.nexus.rule.plan;

import java.util.UUID;

/** One logical rule's explicit rank inside a mutual-exclusion group (smaller rank wins). */
public record ExclusionMembership(UUID groupId, UUID ruleId, int rank) {}
