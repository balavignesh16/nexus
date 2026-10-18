package com.nexus.intelligence.explain;

import com.nexus.intelligence.evidence.EvidenceBundle;
import com.nexus.intelligence.evidence.EvidenceItem;
import com.nexus.intelligence.evidence.Presence;
import com.nexus.rule.ai.Claim;
import com.nexus.rule.ai.ClaimTier;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Independent check of an {@link Explanation} against the evidence it was built from: every FACT resolves to a PRESENT or ABSENT item whose value equals the
 * asserted value, every INFERENCE cites resolving support and no direct reference, every UNKNOWN cites nothing, every cited item exists in the bundle, and an
 * abstention carries no FACT. Returns the list of problems (empty means grounded). Used before an explanation leaves the service and by the evaluation suite.
 */
public final class ExplanationVerifier {
    private ExplanationVerifier() {}

    public static List<String> verify(EvidenceBundle bundle, Explanation explanation) {
        List<String> problems = new ArrayList<>();
        if (explanation.abstained() && explanation.claims().stream().anyMatch(c -> c.tier() == ClaimTier.FACT)) problems.add("an abstention carries a FACT");
        if (explanation.abstained() && (explanation.abstentionReason() == null || explanation.abstentionReason().isBlank())) problems.add("an abstention states no reason");
        int index = 0;
        for (Claim claim : explanation.claims()) {
            String where = "claim " + index++ + " (" + claim.tier() + ")";
            if (claim.text() == null || claim.text().isBlank()) problems.add(where + " has no text");
            switch (claim.tier()) {
                case FACT -> {
                    var item = bundle.resolve(claim.evidenceRef());
                    if (item.isEmpty()) problems.add(where + " cites an unresolvable reference " + claim.evidenceRef());
                    else if (item.get().presence() == Presence.NOT_RECORDED) problems.add(where + " states a FACT about an item that is NOT_RECORDED");
                    else if (!same(item.get().value(), claim.assertedValue())) problems.add(where + " asserts " + claim.assertedValue() + " but the evidence holds " + item.get().value());
                    if (!claim.supportingEvidenceRefs().isEmpty()) problems.add(where + " is a FACT with supporting references");
                }
                case INFERENCE -> {
                    if (claim.evidenceRef() != null) problems.add(where + " is an INFERENCE with a direct reference");
                    if (claim.supportingEvidenceRefs().isEmpty()) problems.add(where + " has no support");
                    for (String ref : claim.supportingEvidenceRefs()) if (bundle.resolve(ref).isEmpty()) problems.add(where + " cites an unresolvable reference " + ref);
                }
                case UNKNOWN -> {
                    if (claim.evidenceRef() != null || claim.assertedValue() != null || !claim.supportingEvidenceRefs().isEmpty()) problems.add(where + " is UNKNOWN but cites evidence");
                }
            }
        }
        for (EvidenceItem cited : explanation.evidence()) {
            var item = bundle.resolve(cited.ref());
            if (item.isEmpty() || !item.get().equals(cited)) problems.add("the cited item " + cited.ref() + " is not the bundle's item");
        }
        return problems;
    }

    static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number x && b instanceof Number y) return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        return Objects.equals(a, b) || String.valueOf(a).equals(String.valueOf(b));
    }
}
