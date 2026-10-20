package com.nexus.intelligence.draft;

import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.persistence.RuleVersionStore;
import com.nexus.rule.plan.ActionArbiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * P6-043: compares a draft's command targets with the ACTIVE rules and their mutual-exclusion groups, from the persisted Phase 3 definitions.
 *
 * <p>Read-only: it never activates, approves, executes or writes. It reads inside one REPEATABLE READ snapshot, so the active definitions and the group
 * memberships it compares belong to one consistent moment. Only a rule's ACTIVE version counts (a superseded version, a pending edit, a paused, draft,
 * validated or archived rule does not govern the target now); such rules are reported as ignored, with the reason, never as conflicts. The target key is the
 * arbiter's own ({@link ActionArbiter#targetKey}), so device, capability and relay channel scope are the same ones the live arbitration uses.
 *
 * <p>When the answer cannot be determined (a command target that cannot be computed, a failed read, an unreadable definition or too many active rules to
 * compare) the status is {@code UNRESOLVED}: the result never says CLEAR on a comparison that was not made. Statuses: CLEAR, CONFLICTS, UNRESOLVED,
 * NOT_APPLICABLE (the draft sends no command). This is a comparison against the rules that exist when it runs; a rule activated afterwards is not seen,
 * which is why the approval step compares again.
 */
@Component
public class DraftConflictAnalyzer {
    public static final int MAX_ACTIVE_RULES = 2000;

    public record ActiveRule(UUID ruleId, UUID versionId, long rowRevision, Rule definition) {}
    public record Membership(UUID groupId, String groupName, UUID ruleId, int rank, String ruleLifecycle) {}
    public record InactiveRule(UUID ruleId, String lifecycle, Rule definition) {}

    /** The persisted facts the comparison needs; an interface so a failing source can be injected in tests. */
    public interface ConflictFacts {
        List<ActiveRule> activeRules();
        List<Membership> groupsOf(Collection<UUID> ruleIds);
        List<InactiveRule> nonActiveRules();
        /** Fingerprint of the persisted state the comparison depends on, read in the same snapshot (see V43). */
        String stateFingerprint(Collection<String> targetKeys, UUID excludedRule);
    }

    private final ConflictFacts facts;

    @Autowired
    public DraftConflictAnalyzer(RuleVersionStore rules, JdbcTemplate jdbc) { this(new JdbcFacts(rules, jdbc)); }

    public DraftConflictAnalyzer(ConflictFacts facts) { this.facts = facts; }

    /** Entry point used with a Spring proxy: a single read-only snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> analyze(Rule draft) { return compare(draft); }

    public Map<String, Object> compare(Rule draft) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analyzer", "DraftConflictAnalyzer/v1");
        Map<String, String> draftTargets = new LinkedHashMap<>();   // targetKey -> equivalenceKey
        List<RuleAction> commands = new ArrayList<>();
        for (RuleAction a : draft.actions()) {
            if (a != null && "SEND_COMMAND".equals(a.actionType())) commands.add(a);
        }
        if (commands.isEmpty()) {
            out.put("status", "NOT_APPLICABLE");
            out.put("reason", "the draft sends no command, so it has no command target to share with another rule");
            out.put("findings", List.of());
            return out;
        }
        for (RuleAction a : commands) {
            String key = ActionArbiter.targetKey(a);
            if (key == null) return unresolved(out, "TARGET_NOT_COMPUTABLE", "a SEND_COMMAND action has no device or capability, so its target cannot be compared");
            draftTargets.put(key, ActionArbiter.equivalenceKey(a));
        }
        List<ActiveRule> active;
        List<InactiveRule> inactive;
        try {
            active = facts.activeRules();
            inactive = facts.nonActiveRules();
        } catch (RuntimeException unreadable) {
            return unresolved(out, "RULES_UNREADABLE", "the persisted rules could not be read: " + unreadable.getClass().getSimpleName());
        }
        if (active.size() > MAX_ACTIVE_RULES) return unresolved(out, "TOO_MANY_ACTIVE_RULES", active.size() + " active rules exceed the bound of " + MAX_ACTIVE_RULES);

        List<ActiveRule> sharing = new ArrayList<>();
        for (ActiveRule r : active) if (commandTargets(r.definition()).stream().anyMatch(draftTargets::containsKey)) sharing.add(r);
        List<InactiveRule> ignoredRules = new ArrayList<>();
        for (InactiveRule r : inactive) if (commandTargets(r.definition()).stream().anyMatch(draftTargets::containsKey)) ignoredRules.add(r);

        String fingerprint;
        try {
            fingerprint = facts.stateFingerprint(draftTargets.keySet(), draft.ruleId());
        } catch (RuntimeException unreadable) {
            return unresolved(out, "STATE_FINGERPRINT_UNAVAILABLE", "the state fingerprint could not be read: " + unreadable.getClass().getSimpleName());
        }
        List<Membership> memberships;
        try {
            Set<UUID> ids = new LinkedHashSet<>();
            sharing.forEach(r -> ids.add(r.ruleId()));
            ignoredRules.forEach(r -> ids.add(r.ruleId()));
            memberships = ids.isEmpty() ? List.of() : facts.groupsOf(ids);
        } catch (RuntimeException unreadable) {
            return unresolved(out, "EXCLUSION_GROUPS_UNREADABLE", "the exclusion groups could not be read: " + unreadable.getClass().getSimpleName());
        }

        List<Map<String, Object>> findings = new ArrayList<>(), ignored = new ArrayList<>();
        for (ActiveRule r : sharing) {
            for (RuleAction theirs : r.definition().actions()) {
                String key = ActionArbiter.targetKey(theirs);
                if (key == null || !draftTargets.containsKey(key)) continue;
                findings.add(arbitrationFinding(draft, r, key, draftTargets.get(key), ActionArbiter.equivalenceKey(theirs)));
                for (Membership m : memberships) {
                    if (!m.ruleId().equals(r.ruleId())) continue;
                    List<String> others = memberships.stream().filter(o -> o.groupId().equals(m.groupId()) && !o.ruleId().equals(r.ruleId()))
                            .map(o -> o.ruleId() + ("ACTIVE".equals(o.ruleLifecycle()) ? "(active)" : "(" + o.ruleLifecycle().toLowerCase() + ", not enforced)")).sorted().toList();
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("code", "MUTUAL_EXCLUSION_TARGET_GOVERNED");
                    f.put("severity", "CONFLICT");
                    f.put("ruleId", r.ruleId().toString());
                    f.put("ruleName", r.definition().name());
                    f.put("target", key);
                    f.put("exclusionGroupId", m.groupId().toString());
                    f.put("exclusionGroupName", m.groupName());
                    f.put("memberRank", m.rank());
                    f.put("otherGroupMembers", others);
                    f.put("reason", "Active rule '" + r.definition().name() + "' (" + r.ruleId() + ") commands " + key + " and is a rank-" + m.rank() + " member of exclusion group '"
                            + m.groupName() + "'. The draft would command the same target without being a member, so the group's one-winner guarantee would no longer cover it.");
                    findings.add(f);
                }
                break;   // one finding set per active rule and target
            }
        }
        for (InactiveRule r : ignoredRules) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("code", "IGNORED_NOT_ACTIVE");
            i.put("ruleId", r.ruleId().toString());
            i.put("ruleName", r.definition().name());
            i.put("lifecycle", r.lifecycle());
            i.put("reason", "rule '" + r.definition().name() + "' shares a command target but its lifecycle is " + r.lifecycle() + ", so it does not command that target now");
            ignored.add(i);
        }
        boolean conflict = findings.stream().anyMatch(f -> "CONFLICT".equals(f.get("severity")));
        out.put("status", conflict ? "CONFLICTS" : "CLEAR");
        out.put("findings", findings);
        out.put("ignored", ignored);
        out.put("consideredActiveRules", active.size());
        out.put("consideredTargets", new ArrayList<>(draftTargets.keySet()));
        out.put("snapshotDigest", digest(sharing, memberships, ignoredRules));
        out.put("targetKeys", new ArrayList<>(new TreeSet<>(draftTargets.keySet())));
        out.put("stateFingerprint", fingerprint);
        return out;
    }

    private Map<String, Object> arbitrationFinding(Rule draft, ActiveRule theirs, String target, String ourEquivalence, String theirEquivalence) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("ruleId", theirs.ruleId().toString());
        f.put("ruleName", theirs.definition().name());
        f.put("target", target);
        f.put("ruleVersionId", theirs.versionId().toString());
        if (ourEquivalence.equals(theirEquivalence)) {
            f.put("code", "TARGET_IDENTICAL_COMMAND");
            f.put("severity", "INFO");
            f.put("reason", "Active rule '" + theirs.definition().name() + "' sends the identical command to " + target + "; the arbiter would coalesce them.");
        } else if (draft.priority() == theirs.definition().priority()) {
            f.put("code", "TARGET_CONFLICT_EQUAL_PRIORITY");
            f.put("severity", "CONFLICT");
            f.put("reason", "Active rule '" + theirs.definition().name() + "' sends a different command to " + target + " at the same priority (" + draft.priority()
                    + "); the arbiter would suppress BOTH when they fire together.");
        } else if (draft.priority() < theirs.definition().priority()) {
            f.put("code", "TARGET_DRAFT_WOULD_OVERRIDE");
            f.put("severity", "CONFLICT");
            f.put("reason", "The draft (priority " + draft.priority() + ") would take precedence over active rule '" + theirs.definition().name() + "' (priority "
                    + theirs.definition().priority() + ") on " + target + " whenever both fire together.");
        } else {
            f.put("code", "TARGET_DRAFT_WOULD_BE_SUPPRESSED");
            f.put("severity", "CONFLICT");
            f.put("reason", "Active rule '" + theirs.definition().name() + "' (priority " + theirs.definition().priority() + ") would take precedence over the draft (priority "
                    + draft.priority() + ") on " + target + " whenever both fire together, so the draft would not command it.");
        }
        return f;
    }

    private static Set<String> commandTargets(Rule rule) {
        Set<String> out = new LinkedHashSet<>();
        for (RuleAction a : rule.actions()) {
            String k = a == null ? null : ActionArbiter.targetKey(a);
            if (k != null) out.add(k);
        }
        return out;
    }

    private Map<String, Object> unresolved(Map<String, Object> out, String code, String reason) {
        out.put("status", "UNRESOLVED");
        out.put("unresolvedCode", code);
        out.put("reason", reason + ". The draft is not declared free of conflicts.");
        out.put("findings", List.of());
        return out;
    }

    private static String digest(List<ActiveRule> sharing, List<Membership> memberships, List<InactiveRule> ignored) {
        TreeSet<String> lines = new TreeSet<>();
        sharing.forEach(r -> lines.add("A|" + r.ruleId() + "|" + r.versionId() + "|" + r.rowRevision()));
        ignored.forEach(r -> lines.add("I|" + r.ruleId() + "|" + r.lifecycle()));
        memberships.forEach(m -> lines.add("M|" + m.groupId() + "|" + m.ruleId() + "|" + m.rank() + "|" + m.ruleLifecycle()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Production facts: the persisted Phase 3 tables, read through the rule store. */
    static final class JdbcFacts implements ConflictFacts {
        private final RuleVersionStore rules;
        private final JdbcTemplate jdbc;

        JdbcFacts(RuleVersionStore rules, JdbcTemplate jdbc) { this.rules = rules; this.jdbc = jdbc; }

        @Override public List<ActiveRule> activeRules() {
            return rules.list(true).stream().map(v -> new ActiveRule(v.ruleId(), v.activeVersionId(), v.rowRevision(), v.definition())).toList();
        }

        @Override public List<InactiveRule> nonActiveRules() {
            return rules.list(false).stream().filter(v -> !"ACTIVE".equals(v.lifecycle()) || !List.of("PASSED", "NOT_REQUIRED").contains(v.reconciliation()))
                    .map(v -> new InactiveRule(v.ruleId(), "ACTIVE".equals(v.lifecycle()) ? "ACTIVE_UNRECONCILED" : v.lifecycle(), v.definition())).toList();
        }

        @Override public String stateFingerprint(Collection<String> targetKeys, UUID excludedRule) {
            return jdbc.queryForObject("SELECT ai_conflict_fingerprint(?::text[], ?)", String.class, targetKeys.toArray(new String[0]), excludedRule);
        }

        @Override public List<Membership> groupsOf(Collection<UUID> ruleIds) {
            String marks = String.join(",", ruleIds.stream().map(i -> "?").toList());
            return jdbc.query("""
                    SELECT m.group_id, g.name, m.rule_id, m.rank, r.lifecycle
                    FROM rule_exclusion_members m
                    JOIN rule_exclusion_groups g ON g.group_id = m.group_id
                    JOIN rules r ON r.rule_id = m.rule_id
                    WHERE m.group_id IN (SELECT group_id FROM rule_exclusion_members WHERE rule_id IN (""" + marks + """
                    ))
                    ORDER BY m.group_id, m.rank, m.rule_id
                    """, (rs, n) -> new Membership(rs.getObject("group_id", UUID.class), rs.getString("name"), rs.getObject("rule_id", UUID.class), rs.getInt("rank"), rs.getString("lifecycle")),
                    ruleIds.toArray());
        }
    }
}
