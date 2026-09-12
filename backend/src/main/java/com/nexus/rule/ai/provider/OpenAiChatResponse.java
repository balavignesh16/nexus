package com.nexus.rule.ai.provider;

import java.util.List;

/**
 * OpenAI-compatible Chat Completions response envelope (Tier 4 Phase 2B) -
 * package-private. The actual model-produced candidate JSON lives nested
 * inside choices[0].message.content as a JSON STRING, not as this
 * envelope itself - OpenAiReasoningBoundary is responsible for extracting
 * that inner string before anything reaches AiCandidateParser.
 */
record OpenAiChatResponse(List<OpenAiChoice> choices) {}
