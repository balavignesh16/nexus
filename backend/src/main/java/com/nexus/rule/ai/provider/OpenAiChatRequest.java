package com.nexus.rule.ai.provider;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** OpenAI-compatible Chat Completions request body (Tier 4 Phase 2B) - package-private, confined to this adapter. */
record OpenAiChatRequest(
    String model,
    List<OpenAiMessage> messages,
    double temperature,
    @JsonProperty("max_tokens") int maxTokens,
    @JsonProperty("response_format") OpenAiResponseFormat responseFormat
) {}
