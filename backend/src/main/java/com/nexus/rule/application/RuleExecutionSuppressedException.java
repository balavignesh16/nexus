package com.nexus.rule.application;

/** Pre-acceptance suppression, never a publication/ACK/physical failure. */
public class RuleExecutionSuppressedException extends RuntimeException {
    public RuleExecutionSuppressedException(String reason) { super(reason); }
}
