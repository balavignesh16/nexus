package com.nexus.rule.dryrun;

import com.nexus.rule.domain.ConditionEvidence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Versioned (schemaVersion 1), additive result of a dry-run. EVERYTHING in it is hypothetical: no match, plan, command, publication,
 * cooldown, latch, timer or lifecycle change was made, and no candidate was dispatched, accepted, published or acknowledged. Identifiers
 * prefixed "sim-" are presentation-only, are not UUIDs and can never be used as a production request or command id.
 */
public record DryRunResponse(int schemaVersion, String mode, boolean hypothetical, Instant simulatedAt, String timeSource, UUID scopeDeviceId,
        List<VersionResult> versions, List<Candidate> candidates, List<StateEffect> hypotheticalEffects, List<Diagnostic> diagnostics, String notice) {

    public static final int SCHEMA_VERSION = 1;
    public static final String NOTICE = "Hypothetical result: nothing was matched, planned, dispatched, published, acknowledged or persisted. "
        + "A selected candidate is a proposal, not an accepted command and not physical actuation.";

    /** Outcome per version: NOT_ELIGIBLE (trigger not subscribed; nothing evaluated) or a RuleDecisionLogic.Outcome name. */
    public record VersionResult(String simulatedVersionId, UUID ruleId, UUID versionId, long revision, long executionEpoch, String name, int priority,
            String source, boolean triggerEligible, String outcome, boolean matched, String message, int lane,
            List<ConditionEvidence> conditions, List<Map<String, Object>> contractDiagnostics, Cooldown cooldown, Repeat repeat, Ownership ownership) {}

    /**
     * Whether production could execute this version at all: the rule is ACTIVE, this is its active version and its reconciliation passed. Null for an
     * unsaved inline definition (it has no production owner). This is a read of the stored ownership, NOT the production authorization check.
     */
    public record Ownership(String lifecycle, boolean activeVersion, String reconciliation, boolean executableInProduction) {}

    public record Cooldown(Integer windowSeconds, String scope, Instant lastMatchedAt, boolean cooling) {}

    public record Repeat(String policy, boolean edgeLatched) {}

    /** A proposed action with its arbitration result. {@code wouldBeSelected} means arbitration alone would pick it; it is NOT a prediction that acceptance would succeed. {@code winnerId} references another candidate's simulatedId (a final SELECTED primary) or is null. */
    public record Candidate(String simulatedId, int lane, UUID ruleId, UUID versionId, int ordinal, String actionType, String targetKey, int priority,
            String disposition, String reason, String winnerId, boolean wouldBeSelected, List<Map<String, Object>> exclusionGroups,
            Map<String, Object> parameters, String acceptanceAuthorization) {
        /** Production's guarded acceptance and authorization are never invoked by a dry-run: SELECTED is an arbitration outcome only. */
        public static final String NOT_EVALUATED = "NOT_EVALUATED";
    }

    /** What production WOULD change if the same decision were live. Never applied. */
    public record StateEffect(String kind, UUID ruleId, String detail, Object from, Object to) {}

    public record Diagnostic(String code, String severity, String message, String path) {}
}
