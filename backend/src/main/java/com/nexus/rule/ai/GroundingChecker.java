package com.nexus.rule.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic grounding validation (Tier 4 Phase 2A) - operates purely on
 * a (context, candidate explanation) pair, independent of how the
 * candidate was produced. Usable unchanged by DeterministicExplanationRenderer's
 * output and by a future AI reasoning boundary's output.
 *
 * Invalid claims are rejected individually, never silently downgraded to
 * UNKNOWN - a rejected claim's (possibly wrong) text is never surfaced
 * under a softer label. If every claim is rejected, the caller must treat
 * this as a signal that the deterministic fallback is required.
 *
 * STRUCTURAL vs SEMANTIC boundary (important, intentional limit): this
 * class guarantees that a FACT claim's assertedValue is actually present,
 * unmodified, in the evidence it cites - a purely mechanical check. It
 * does NOT and cannot verify that a claim's free-text `text` field is a
 * faithful natural-language paraphrase of that value. "The structured
 * value being asserted is really in the evidence" is proven; "the sentence
 * describing it is honest" is not, and is not attempted here.
 */
@Component
public class GroundingChecker {

    private static final Pattern ARRAY_SEGMENT = Pattern.compile("^(\\w+)\\[(\\d+)\\]$");
    private static final Object NOT_FOUND = new Object();

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

        // Platform-owned mandatory safety claims (Tier 4 Phase 2A): appended
        // unconditionally whenever at least one AI-proposed claim survived
        // grounding, regardless of whether the candidate already proposed a
        // similarly-worded UNKNOWN claim of its own - no attempt is made to
        // detect/deduplicate that; minor redundancy is accepted in exchange
        // for never depending on the AI's own phrasing for a safety-critical
        // disclosure. If zero AI claims survive, this method intentionally
        // adds nothing further - the caller falls back to
        // DeterministicExplanationRenderer, whose own output already
        // includes these same mandatory claims unconditionally.
        if (!accepted.isEmpty()) {
            if (context.deviceId() == null) {
                accepted.add(new Claim(ClaimTier.UNKNOWN, DeterministicExplanationRenderer.DEVICE_UNKNOWN, null, null, List.of()));
            }
            accepted.add(new Claim(ClaimTier.UNKNOWN, DeterministicExplanationRenderer.CAUSAL_UNCERTAINTY, null, null, List.of()));
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
        return ExecutionTruthfulness.noteFor(ExecutionTruthfulness.typesOf(context));
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

    /**
     * STRUCTURAL VALUE VERIFICATION: a FACT claim is grounded only if its
     * evidenceRef resolves AND the resolved value equals assertedValue -
     * not merely that the path exists. This is the mechanism that rejects
     * a claim stating "the value was 40.2" while citing evidence that
     * actually resolves to 34.7.
     */
    private String validateFact(Claim claim, Map<String, Object> tree, List<String> supporting) {
        if (claim.evidenceRef() == null) {
            return "FACT claim missing evidenceRef: " + claim.text();
        }
        if (!supporting.isEmpty()) {
            return "FACT claim must not have supportingEvidenceRefs: " + claim.text();
        }
        Object resolved = resolveOrNotFound(tree, claim.evidenceRef());
        if (resolved == NOT_FOUND) {
            return "FACT evidenceRef does not resolve against context: " + claim.evidenceRef();
        }
        if (!valuesMatch(resolved, claim.assertedValue())) {
            return "FACT assertedValue does not match evidence at " + claim.evidenceRef()
                    + ": claimed=" + claim.assertedValue() + " actual=" + resolved;
        }
        return null;
    }

    private String validateUnknown(Claim claim, List<String> supporting) {
        if (claim.evidenceRef() != null) {
            return "UNKNOWN claim must not have an evidenceRef: " + claim.text();
        }
        if (claim.assertedValue() != null) {
            return "UNKNOWN claim must not have an assertedValue: " + claim.text();
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
        if (claim.assertedValue() != null) {
            return "INFERENCE claim must not have an assertedValue: " + claim.text();
        }
        if (supporting.isEmpty()) {
            return "INFERENCE claim requires supportingEvidenceRefs: " + claim.text();
        }
        for (String ref : supporting) {
            if (resolveOrNotFound(tree, ref) == NOT_FOUND) {
                return "INFERENCE supportingEvidenceRef does not resolve against context: " + ref;
            }
        }
        return null;
    }

    /**
     * Type-tolerant equality between a resolved context value and an
     * asserted value. Numbers (including numeric strings, since a model's
     * JSON output may represent a number either way) compare via
     * BigDecimal; everything else (strings, booleans, enum-valued fields,
     * ISO-8601 timestamp strings) compares via its string representation.
     * null only matches null.
     */
    private boolean valuesMatch(Object resolved, Object asserted) {
        if (resolved == null || asserted == null) {
            return resolved == null && asserted == null;
        }
        if (isNumeric(resolved) && isNumeric(asserted)) {
            return toBigDecimal(resolved).compareTo(toBigDecimal(asserted)) == 0;
        }
        return String.valueOf(resolved).equals(String.valueOf(asserted));
    }

    private boolean isNumeric(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (value instanceof String s) {
            try {
                new BigDecimal(s);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private BigDecimal toBigDecimal(Object value) {
        return new BigDecimal(value.toString());
    }

    /**
     * Resolves a dot/bracket path (e.g. "evaluationEvidence.evaluatedConditions[0].actualValue")
     * against the normalized context tree only, returning the sentinel
     * NOT_FOUND when the path does not exist. A resolved value of Java
     * null is a distinct, valid outcome (the path exists and legitimately
     * holds no value) - Optional cannot represent this distinction cleanly
     * (Optional.of(null) throws), hence the sentinel.
     */
    private Object resolveOrNotFound(Map<String, Object> tree, String path) {
        Object current = tree;
        for (String segment : path.split("\\.")) {
            Matcher matcher = ARRAY_SEGMENT.matcher(segment);
            String key = matcher.matches() ? matcher.group(1) : segment;

            if (!(current instanceof Map<?, ?> map) || !map.containsKey(key)) {
                return NOT_FOUND;
            }
            current = map.get(key);

            if (matcher.matches()) {
                int index = Integer.parseInt(matcher.group(2));
                if (!(current instanceof List<?> list) || index < 0 || index >= list.size()) {
                    return NOT_FOUND;
                }
                current = list.get(index);
            }
        }
        return current;
    }
}
