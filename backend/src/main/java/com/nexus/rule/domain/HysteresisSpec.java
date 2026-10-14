package com.nexus.rule.domain;

/**
 * Explicit numeric hysteresis (C14): the owning condition's expectedValue is the ENTER threshold and {@code exit} the EXIT threshold,
 * both in the capability's established effective unit. Nothing is inferred: a condition without this block has no hysteresis.
 * For GT/GTE the exit must be strictly below the enter; for LT/LTE strictly above.
 */
public record HysteresisSpec(String exit) {}
