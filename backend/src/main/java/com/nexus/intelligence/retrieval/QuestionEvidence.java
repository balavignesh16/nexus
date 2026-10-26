package com.nexus.intelligence.retrieval;

import java.util.List;

/** The results gathered for ONE question. {@code limitReached} says some requested lookups were not run (too many lookups or the total time was used up). */
public record QuestionEvidence(List<EvidenceResult> results, List<String> notices, boolean limitReached) {
    public QuestionEvidence {
        results = List.copyOf(results);
        notices = List.copyOf(notices);
    }
}
