package com.nexus.rule.ai.provider;

/** Tier 4 Phase 2B: the assistant message inside one response choice. */
record OpenAiChoiceMessage(String role, String content) {}
