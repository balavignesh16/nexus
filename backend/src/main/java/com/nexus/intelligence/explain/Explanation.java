package com.nexus.intelligence.explain;

import com.nexus.intelligence.evidence.EvidenceItem;
import com.nexus.intelligence.model.ProviderIdentity;
import com.nexus.rule.ai.Claim;

import java.time.Instant;
import java.util.List;

/**
 * A deterministic, evidence-grounded explanation. {@code claims} are FACT (value equals the cited persisted fact), INFERENCE (derived from cited facts and
 * never worded as observed) or UNKNOWN (the platform has no record). {@code platformStatements} are fixed, platform-owned sentences about what the facts do
 * and do not prove. When the needed evidence is missing the explanation {@code abstained} and says why; it then carries no FACT claim. {@code hypothetical}
 * is true when the result comes from a dry-run evaluation of persisted state rather than from a historical record. {@code evidence} lists every cited item. {@code provenance} names the provider/model/configuration that produced any model text ({@link ProviderIdentity#NONE} when none did).
 */
public record Explanation(ExplanationKind kind, String subject, String summary, List<Claim> claims, List<String> platformStatements, boolean abstained,
                          String abstentionReason, boolean hypothetical, Instant evaluatedAt, List<EvidenceItem> evidence, String generatedBy,
                          ProviderIdentity provenance) {
    public static final String DETERMINISTIC = "DETERMINISTIC";

    public Explanation {
        claims = claims == null ? List.of() : List.copyOf(claims);
        platformStatements = platformStatements == null ? List.of() : List.copyOf(platformStatements);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        provenance = provenance == null ? ProviderIdentity.NONE : provenance;
    }

    /** A deterministic explanation: no model produced any of its text. */
    public Explanation(ExplanationKind kind, String subject, String summary, List<Claim> claims, List<String> platformStatements, boolean abstained,
                       String abstentionReason, boolean hypothetical, Instant evaluatedAt, List<EvidenceItem> evidence, String generatedBy) {
        this(kind, subject, summary, claims, platformStatements, abstained, abstentionReason, hypothetical, evaluatedAt, evidence, generatedBy, ProviderIdentity.NONE);
    }

    public static Explanation abstention(ExplanationKind kind, String subject, String reason, Instant at) {
        return new Explanation(kind, subject, "No explanation can be given: " + reason, List.of(), List.of(), true, reason, false, at, List.of(), DETERMINISTIC);
    }
}
