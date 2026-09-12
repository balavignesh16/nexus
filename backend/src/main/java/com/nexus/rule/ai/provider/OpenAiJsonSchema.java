package com.nexus.rule.ai.provider;

import java.util.Map;

/** The strict JSON schema declaration for structured output (Tier 4 Phase 2B). */
record OpenAiJsonSchema(String name, boolean strict, Map<String, Object> schema) {}
