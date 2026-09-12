package com.nexus.rule.ai.provider;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Tier 4 Phase 2B: {"type": "json_schema", "json_schema": {...}}. */
record OpenAiResponseFormat(String type, @JsonProperty("json_schema") OpenAiJsonSchema jsonSchema) {}
