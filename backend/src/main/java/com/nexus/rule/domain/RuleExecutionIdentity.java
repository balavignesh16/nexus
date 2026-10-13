package com.nexus.rule.domain;

import java.util.UUID;

/** Definition revision, mutation revision and execution generation are distinct. */
public record RuleExecutionIdentity(UUID ruleVersionId, long ruleRevision, long executionEpoch) {}
