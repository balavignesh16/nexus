package com.nexus.intelligence.assistant;

import com.nexus.intelligence.evidence.EvidenceItem;

import java.time.Instant;
import java.util.List;

/**
 * One answer of the NEXUS Assistant. Every sentence in {@code statements} and {@code additional} is written by the PLATFORM from stored evidence; a FACT carries the
 * evidence references that support it (each resolves in {@code sources}), a LIMIT states what the evidence cannot show, a NOTICE explains a boundary of the assistant.
 * No text written by a model is ever part of an answer: a model, when one is configured, may only reorder which statements come first.
 */
public record AssistantAnswer(String conversationId, int turn, Status status, String routedBy, String orderedBy, List<String> plan, List<Statement> statements, List<Statement> additional,
                              List<EvidenceItem> sources, List<String> notices, String clarification, Instant evaluatedAt) {
    public enum Status { ANSWERED, NEEDS_CLARIFICATION, REFUSED_WRITE, OUT_OF_SCOPE, NO_EVIDENCE, UNAVAILABLE, DRAFT_STORED, DRAFT_NOT_STORED }

    public enum Kind { FACT, LIMIT, NOTICE }

    public record Statement(String id, Kind kind, String text, List<String> refs) {
        public Statement {
            refs = List.copyOf(refs);
        }
    }

    public AssistantAnswer {
        plan = List.copyOf(plan);
        statements = List.copyOf(statements);
        additional = List.copyOf(additional);
        sources = List.copyOf(sources);
        notices = List.copyOf(notices);
    }
}
