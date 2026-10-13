package com.nexus.rule.domain;
import java.util.UUID;
public record RuleProjectionChanged(UUID ruleId,long rowRevision) {}
