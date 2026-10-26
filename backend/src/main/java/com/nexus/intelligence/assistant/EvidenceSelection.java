package com.nexus.intelligence.assistant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a model's choice of evidence. The model may only pick references that were presented to it, each once, most relevant first; an invented reference is
 * rejected (the whole choice is dropped, never silently repaired), and abstaining is accepted only with no references. The selection decides WHICH platform
 * statements are shown and in what order; it contributes no text.
 */
public final class EvidenceSelection {
    private EvidenceSelection() {}

    public enum Code { UNKNOWN_REF, DUPLICATE_REF, TOO_MANY_REFS, ABSTAIN_WITH_REFS, WRONG_TYPE }

    public record Selection(List<String> refs, boolean abstained) {
        public Selection {
            refs = List.copyOf(refs);
        }
    }

    public sealed interface Result permits Accepted, Rejected {}

    public record Accepted(Selection selection) implements Result {}

    public record Rejected(Code code) implements Result {}

    public static Result validate(Map<String, Object> values, Set<String> presented) {
        Object refs = values.get("selectedRefs");
        Object abstain = values.get("abstain");
        if (!(refs instanceof List<?> list) || !(abstain instanceof Boolean abstained)) return new Rejected(Code.WRONG_TYPE);
        if (list.size() > AssistantContracts.MAX_SELECTED_REFS) return new Rejected(Code.TOO_MANY_REFS);
        if (abstained && !list.isEmpty()) return new Rejected(Code.ABSTAIN_WITH_REFS);
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String ref)) return new Rejected(Code.WRONG_TYPE);
            if (!presented.contains(ref)) return new Rejected(Code.UNKNOWN_REF);
            if (!seen.add(ref)) return new Rejected(Code.DUPLICATE_REF);
            out.add(ref);
        }
        return new Accepted(new Selection(out, abstained));
    }
}
