package com.nexus.rule.ai.provider;

/** OpenAI Chat Completions message (Tier 4 Phase 2B) - package-private, never leaves the provider adapter. */
record OpenAiMessage(String role, String content) {}
