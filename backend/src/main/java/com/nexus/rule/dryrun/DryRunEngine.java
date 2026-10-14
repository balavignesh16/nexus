package com.nexus.rule.dryrun;

import com.nexus.rule.application.RuleDecisionLogic;
import com.nexus.rule.application.RuleEvaluator;
import com.nexus.rule.domain.RepeatPolicy;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.rule.domain.RuleMatchedEvent;
import com.nexus.rule.dryrun.DryRunResponse.*;
import com.nexus.rule.plan.ActionArbiter;
import com.nexus.rule.plan.ActionCandidates;
import com.nexus.rule.plan.ActionDecision;
import com.nexus.rule.plan.ExclusionMembership;
import com.nexus.rule.plan.ExclusionMembershipReader;
import com.nexus.rule.snapshot.RuleEvaluationContext;
import com.nexus.rule.snapshot.RuleEvaluationSnapshot;
import com.nexus.rule.trigger.TriggerObservation;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Evaluates already-resolved rule versions against ONE frozen snapshot with the PRODUCTION decision logic, candidate construction and
 * arbitration, and reports the result. It is a pure function of its inputs plus read-only configuration (exclusion memberships): it holds
 * no store, planner, executor, publisher or lifecycle collaborator, so it has no capability to persist, dispatch or publish anything.
 * Mutual-exclusion arbitration runs per lane (the n-th version of a logical rule) so that comparing two versions of one rule never makes
 * them compete with each other.
 */
@Component
public class DryRunEngine {
    private final RuleEvaluator evaluator;
    private final ExclusionMembershipReader memberships;
    public DryRunEngine(RuleEvaluator evaluator, ExclusionMembershipReader memberships) { this.evaluator = evaluator; this.memberships = memberships; }

    /** One selected version. {@code trigger} is the observation addressed to it; ineligible entries are reported but never evaluated. */
    public record Entry(String simulatedVersionId, String source, Rule rule, TriggerObservation trigger, boolean eligible, Ownership ownership) {
        /** An entry whose stored ownership is not known (an inline definition, or a caller that supplies a frozen snapshot directly). */
        public Entry(String simulatedVersionId, String source, Rule rule, TriggerObservation trigger, boolean eligible) { this(simulatedVersionId, source, rule, trigger, eligible, null); }
    }

    /** Runtime gate inputs, already isolated (a request-local copy or supplied seeds). */
    public interface RuntimeInputs {
        Optional<Instant> lastMatched(Rule rule, String scopeKey);
        boolean edgeLatched(Rule rule, UUID scopeDevice);
    }

    public DryRunResponse evaluate(String mode, Instant time, String timeSource, UUID scopeDevice, List<Entry> entries, RuleEvaluationSnapshot snapshot,
            RuntimeInputs runtime, List<Diagnostic> callerDiagnostics) {
        var diagnostics = new ArrayList<>(callerDiagnostics);   // never mutate (or require a mutable) caller list
        var results = new ArrayList<VersionResult>();
        var effects = new ArrayList<StateEffect>();
        var matchesByLane = new TreeMap<Integer, List<RuleMatchedEvent>>();
        var occurrences = new HashMap<UUID, Integer>();
        var inlineRules = new HashSet<UUID>();
        for (Entry entry : entries) if (INLINE.equals(entry.source())) inlineRules.add(entry.rule().ruleId());
        int eligibleIndex = 0;
        for (Entry entry : entries) {
            Rule rule = entry.rule();
            int lane = occurrences.merge(rule.ruleId(), 1, Integer::sum) - 1;
            var identity = rule.executionIdentity();
            if (!entry.eligible()) {
                results.add(new VersionResult(entry.simulatedVersionId(), shownRule(entry), shownVersion(entry), identity.ruleRevision(), identity.executionEpoch(),
                    rule.name(), rule.priority(), entry.source(), false, "NOT_ELIGIBLE", false,
                    "This version is not subscribed to the supplied trigger, so it is not evaluated (production would not evaluate it either).", lane,
                    List.of(), List.of(), null, null, entry.ownership()));
                continue;
            }
            var captured = snapshot.rules().get(eligibleIndex++);
            var context = new RuleEvaluationContext(UUID.randomUUID(), snapshot, captured, entry.trigger());
            UUID scope = captured.scopeDeviceId();
            String scopeKey = rule.cooldownScopeKey(scope);
            Optional<Instant> last = runtime.lastMatched(rule, scopeKey);
            boolean cooling = rule.cooldownSeconds() != null && last.isPresent() && time.isBefore(last.get().plusSeconds(rule.cooldownSeconds()));
            boolean edgeLatched = rule.repeat() == RepeatPolicy.EDGE_TRUE && scope != null && runtime.edgeLatched(rule, scope);
            var decision = RuleDecisionLogic.decide(evaluator, rule, context, cooling, () -> edgeLatched);
            RuleEvaluationResult result = decision.result();
            results.add(new VersionResult(entry.simulatedVersionId(), shownRule(entry), shownVersion(entry), identity.ruleRevision(), identity.executionEpoch(),
                rule.name(), rule.priority(), entry.source(), true, decision.outcome().name(), decision.matched(),
                result == null ? outcomeMessage(decision) : result.message(), lane,
                result == null ? List.of() : result.evidence(), decision.diagnostics(),
                new Cooldown(rule.cooldownSeconds(), rule.cooldownScope().name(), last.orElse(null), cooling),
                new Repeat(rule.repeat().name(), edgeLatched), entry.ownership()));
            for (var effect : decision.hysteresis()) {
                boolean before = snapshot.hysteresis().getOrDefault(RuleEvaluationSnapshot.hysteresisKey(rule.ruleId(), effect.conditionId(), effect.scopeDeviceId()), false);
                effects.add(new StateEffect("HYSTERESIS_LATCH", rule.ruleId(), "condition " + effect.conditionId(), before, effect.latchedAfter()));
            }
            if (decision.edgeRearm() && edgeLatched) effects.add(new StateEffect("EDGE_REARMED", rule.ruleId(), "definite false evidence", true, false));
            if (decision.matched()) {
                var ownership = entry.ownership();
                if (ownership != null && !ownership.executableInProduction())
                    diagnostics.add(new Diagnostic("NOT_EXECUTABLE_IN_PRODUCTION", "WARNING", "'" + rule.name() + "' is " + ownership.lifecycle()
                        + (ownership.activeVersion() ? "" : ", and this is not its active version") + " (reconciliation " + ownership.reconciliation()
                        + "): production would not execute it even though arbitration may select its candidates.", entry.simulatedVersionId()));
                if (INLINE.equals(entry.source()))
                    diagnostics.add(new Diagnostic("UNSAVED_DEFINITION", "INFO", "This is an unsaved definition with no production owner; saving and activating it are separate steps.", entry.simulatedVersionId()));
                if (rule.repeat() == RepeatPolicy.EDGE_TRUE && scope != null) effects.add(new StateEffect("EDGE_LATCH_CLAIMED", rule.ruleId(), "edge consumed by this match", false, true));
                if (rule.cooldownSeconds() != null) effects.add(new StateEffect("COOLDOWN_ADVANCED", rule.ruleId(), "scope '" + scopeKey + "'", last.orElse(null), time));
                matchesByLane.computeIfAbsent(lane, k -> new ArrayList<>()).add(new RuleMatchedEvent(UUID.randomUUID(), time, result, entry.trigger().correlationId()));
            }
        }
        var candidates = new ArrayList<Candidate>();
        for (var lane : matchesByLane.entrySet()) {
            var built = ActionCandidates.build(lane.getValue());
            var memberRows = memberships.read(built.stream().map(c -> c.rule().ruleId()).distinct().toList());
            var decisions = ActionArbiter.arbitrate(built, memberRows);
            var simulatedIds = new HashMap<UUID, String>();
            int n = 1;
            for (ActionDecision d : decisions) simulatedIds.put(d.candidate().candidateId(), "sim-l" + lane.getKey() + "-c" + n++);
            var groupsByRule = new HashMap<UUID, List<Map<String, Object>>>();
            for (ExclusionMembership m : memberRows)
                groupsByRule.computeIfAbsent(m.ruleId(), r -> new ArrayList<>()).add(new LinkedHashMap<>(Map.of("groupId", m.groupId().toString(), "rank", m.rank())));
            for (ActionDecision d : decisions) {
                var c = d.candidate();
                boolean inline = inlineRules.contains(c.rule().ruleId());
                candidates.add(new Candidate(simulatedIds.get(c.candidateId()), lane.getKey(), inline ? null : c.rule().ruleId(), inline ? null : c.versionId(), c.ordinal(), c.action().actionType(),
                    c.targetKey(), c.priority(), d.disposition().name(), d.reason(), d.winnerCandidateId() == null ? null : simulatedIds.get(d.winnerCandidateId()),
                    d.disposition() == ActionDecision.Disposition.SELECTED, groupsByRule.getOrDefault(c.rule().ruleId(), List.of()), c.action().parameters() == null ? Map.of() : c.action().parameters(),
                    Candidate.NOT_EVALUATED));
            }
        }
        // C19: SUPPLIED_STATE is evaluated against REAL configured device contracts; a device that does not exist is diagnosed, never invented.
        if (results.stream().anyMatch(v -> v.contractDiagnostics().stream().anyMatch(d -> "MISSING_DEVICE".equals(String.valueOf(d.get("code"))))))
            diagnostics.add(new Diagnostic("DEVICE_NOT_CONFIGURED", "WARNING",
                "The scope device does not exist. A dry-run evaluates against real configured device contracts; hypothetical devices and hypothetical installed contracts are not supported.", "scope"));
        return new DryRunResponse(DryRunResponse.SCHEMA_VERSION, mode, true, time, timeSource, scopeDevice, results, candidates, effects,
            diagnostics, DryRunResponse.NOTICE);
    }

    private static final String INLINE = "INLINE";
    /** An inline definition was never stored: it has no real rule or version id to show. */
    private static UUID shownRule(Entry entry) { return INLINE.equals(entry.source()) ? null : entry.rule().ruleId(); }
    private static UUID shownVersion(Entry entry) { return INLINE.equals(entry.source()) ? null : entry.rule().executionIdentity().ruleVersionId(); }

    private static String outcomeMessage(RuleDecisionLogic.Decision decision) {
        return switch (decision.outcome()) {
            case SKIPPED_IN_COOLDOWN -> "Inside the cooldown window: production does not evaluate this rule.";
            case SCOPE_MISSING -> "No device scope could be resolved: production suppresses this rule.";
            case CONTRACT_DIAGNOSTICS -> "The installed device contract does not satisfy this version: production suppresses it.";
            default -> decision.outcome().name();
        };
    }
}
