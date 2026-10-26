package com.nexus.intelligence.explain;

import com.nexus.intelligence.evidence.EvidenceBundle;
import com.nexus.intelligence.evidence.EvidenceItem;
import com.nexus.intelligence.evidence.Presence;
import com.nexus.rule.ai.Claim;
import com.nexus.rule.ai.ClaimTier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds claims strictly from an {@link EvidenceBundle}: a FACT takes its asserted value from the cited item (so it cannot disagree with it), refuses
 * a NOT_RECORDED item (a missing record is UNKNOWN, never a statement), and every cited item is collected for the result.
 */
final class ExplanationBuilder {
    private final EvidenceBundle bundle;
    private final List<Claim> claims = new ArrayList<>();
    private final Set<String> cited = new LinkedHashSet<>();

    ExplanationBuilder(EvidenceBundle bundle) {
        this.bundle = bundle;
    }

    /** A FACT whose asserted value is whatever the cited persisted item holds (null for an ABSENT item). */
    void fact(String text, String ref) {
        EvidenceItem item = bundle.resolve(ref).orElseThrow(() -> new IllegalStateException("no evidence " + ref));
        if (item.presence() == Presence.NOT_RECORDED) throw new IllegalStateException("a FACT cannot cite a NOT_RECORDED item: " + ref);
        cited.add(ref);
        claims.add(new Claim(ClaimTier.FACT, text, ref, item.value(), List.of()));
    }

    /** A derived statement: it cites the facts it connects and is never presented as observed. */
    void inference(String text, String... refs) {
        List<String> support = List.of(refs);
        if (support.isEmpty()) throw new IllegalStateException("an inference needs support");
        for (String ref : support) {
            bundle.resolve(ref).orElseThrow(() -> new IllegalStateException("no evidence " + ref));
            cited.add(ref);
        }
        claims.add(new Claim(ClaimTier.INFERENCE, text, null, null, support));
    }

    /** The platform holds no record: say so, cite nothing. */
    void unknown(String text) {
        claims.add(new Claim(ClaimTier.UNKNOWN, text, null, null, List.of()));
    }

    Explanation build(ExplanationKind kind, String subject, String summary, List<String> platformStatements, boolean hypothetical, Instant at) {
        List<EvidenceItem> evidence = cited.stream().map(r -> bundle.resolve(r).orElseThrow()).toList();
        return new Explanation(kind, subject, summary, claims, platformStatements, false, null, hypothetical, at, evidence, Explanation.DETERMINISTIC);
    }
}
