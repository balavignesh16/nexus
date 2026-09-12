package com.nexus.rule.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic grounding validation (Tier 4 Phase 1) - operates purely on
 * a (context, candidate explanation) pair, independent of how the
 * candidate was produced. Usable unchanged by DeterministicExplanationRenderer's
 * output today and by a future AI reasoning boundary's output later.
 *
 * Invalid claims are rejected individually, never silently downgraded to
 * UNKNOWN - a rejected claim's (possibly wrong) text is never surfaced
 * under a softer label. If every claim is rejected, the caller must treat
 * this as a signal that the deterministic fallback is required.
 */
@Component
public class GroundingChecker {

    private static final Pattern ARRAY_SEGMENT = Pattern.compile("^(\\w+)\\[(\\d+)\\]$");

    private final ObjectMapper objectMapper;

    public GroundingChecker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GroundingResult check(RuleMatchExplanationContext context, RuleMatchExplanation candidate) {
        List<String> reasons = new ArrayList<>();
        List<Claim> sourceClaims = candidate.claims();
        String enforcedActionNote = enforcedActionNote(context);

        if (!context.matchId().equals(candidate.matchId())) {
            reasons.add("matchId mismatch: candidate explanation does not belong to this context");
            RuleMatchExplanation empty = new RuleMatchExplanation(
                    context.matchId(), candidate.summary(), List.of(), enforcedActionNote, candidate.generatedBy());
            return new GroundingResult(empty, sourceClaims.size(), reasons);
        }

        if (sourceClaims.isEmpty()) {
            reasons.add("claims must be non-empty");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> tree = objectMapper.convertValue(context, Map.class);

        List<Claim> accepted = new ArrayList<>();
        int rejected = 0;
        for (Claim claim : sourceClaims) {
            String reason = validateClaim(claim, tree);
            if (reason == null) {
                accepted.add(claim);
            } else {
                rejected++;
                reasons.add(reason);
            }
        }

        // actionNote is a fixed, platform-owned disclaimer, never trusted
        // from the candidate: whether it is present, and what it says, is
        // determined solely by context.actions(), never by
        // candidate.actionNote() - a future AI candidate cannot author,
        // soften, remove, or omit it.
        RuleMatchExplanation validated = new RuleMatchExplanation(
                context.matchId(), candidate.summary(), accepted, enforcedActionNote, candidate.generatedBy());

        return new GroundingResult(validated, rejected, reasons);
    }

    private String enforcedActionNote(RuleMatchExplanationContext context) {
        return context.actions().isEmpty() ? null : DeterministicExplanationRenderer.ACTION_DISCLAIMER;
    }

    private String validateClaim(Claim claim, Map<String, Object> tree) {
        if (claim.tier() == null) {
            return "claim has null tier: " + claim.text();
        }
        if (claim.text() == null || claim.text().isBlank()) {
            return "claim text is blank";
        }
        if (claim.supportingEvidenceRefs() == null) {
            return "claim has null supportingEvidenceRefs (malformed claim shape): " + claim.text();
        }

        List<String> supporting = claim.supportingEvidenceRefs();

        return switch (claim.tier()) {
            case FACT -> validateFact(claim, tree, supporting);
            case UNKNOWN -> validateUnknown(claim, supporting);
            case INFERENCE -> validateInference(claim, tree, supporting);
        };
    }

    private String validateFact(Claim claim, Map<String, Object> tree, List<String> supporting) {
        if (claim.evidenceRef() == null) {
            return "FACT claim missing evidenceRef: " + claim.text();
        }
        if (!resolves(tree, claim.evidenceRef())) {
            return "FACT evidenceRef does not resolve against context: " + claim.evidenceRef();
        }
        if (!supporting.isEmpty()) {
            return "FACT claim must not have supportingEvidenceRefs: " + claim.text();
        }
        return null;
    }

    private String validateUnknown(Claim claim, List<String> supporting) {
        if (claim.evidenceRef() != null) {
            return "UNKNOWN claim must not have an evidenceRef: " + claim.text();
        }
        if (!supporting.isEmpty()) {
            return "UNKNOWN claim must not have supportingEvidenceRefs: " + claim.text();
        }
        return null;
    }

    private String validateInference(Claim claim, Map<String, Object> tree, List<String> supporting) {
        if (claim.evidenceRef() != null) {
            return "INFERENCE claim must not have a direct evidenceRef: " + claim.text();
        }
        if (supporting.isEmpty()) {
            return "INFERENCE claim requires supportingEvidenceRefs: " + claim.text();
        }
        for (String ref : supporting) {
            if (!resolves(tree, ref)) {
                return "INFERENCE supportingEvidenceRef does not resolve against context: " + ref;
            }
        }
        return null;
    }

    /**
     * Resolves a dot/bracket path (e.g. "evaluationEvidence.evaluatedConditions[0].actualValue")
     * against the normalized context tree only. Existence is determined via
     * Map.containsKey, not a null check on the resolved value, since a
     * legitimately null evidence value (e.g. a missing observed value) must
     * still count as "exists" rather than "not found".
     */
    private boolean resolves(Map<String, Object> tree, String path) {
        Object current = tree;
        for (String segment : path.split("\\.")) {
            Matcher matcher = ARRAY_SEGMENT.matcher(segment);
            String key = matcher.matches() ? matcher.group(1) : segment;

            if (!(current instanceof Map<?, ?> map) || !map.containsKey(key)) {
                return false;
            }
            current = map.get(key);

            if (matcher.matches()) {
                int index = Integer.parseInt(matcher.group(2));
                if (!(current instanceof List<?> list) || index < 0 || index >= list.size()) {
                    return false;
                }
                current = list.get(index);
            }
        }
        return true;
    }
}
