package com.nexus.rule.domain;

/**
 * C14 cooldown identity. GLOBAL (the default, and the only behavior of imported rules) is one cooldown per logical rule;
 * PER_SCOPE is an explicit opt-in that keeps one cooldown per resolved device scope.
 */
public enum CooldownScope { GLOBAL, PER_SCOPE }
