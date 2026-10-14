package com.nexus.rule.ai;

/** The persisted rule revision and execution epoch of a match (no version identifier leaves NEXUS). Null components mean unknown. */
public record AiVersionAttribution(Long ruleRevision, Long executionEpoch) {}
