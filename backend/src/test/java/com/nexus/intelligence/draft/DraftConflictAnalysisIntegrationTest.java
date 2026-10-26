package com.nexus.intelligence.draft;

import com.nexus.rule.application.RuleExclusionGroupService;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.TriggerSubscription;
import com.nexus.rule.domain.TriggerType;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-043 on real PostgreSQL: the draft is compared with the persisted ACTIVE rules and their mutual-exclusion groups. Every case names the evidence it relies
 * on; the mutation campaign removes each comparison in turn and these tests must fail.
 */
class DraftConflictAnalysisIntegrationTest extends TypedTriggerSupport {
    @Autowired DraftConflictAnalyzer analyzer;
    @Autowired RuleExclusionGroupService groups;
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    private Rule draftCommanding(UUID target, boolean on, int priority) {
        return new Rule(UUID.randomUUID(), "AI draft", false, priority, List.of(), List.of(ledCommand(target, on)), Map.of("origin", "AI_DRAFT"), 60, null, null);
    }

    private UUID activeCommanding(UUID target, boolean on, int priority, String name) {
        return typedRule(device(), name, priority, TELEMETRY, List.of(temperatureAbove("40")), List.of(ledCommand(target, on)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> findings(Map<String, Object> analysis) { return (List<Map<String, Object>>) analysis.get("findings"); }

    private static Set<String> codes(Map<String, Object> analysis) {
        Set<String> out = new HashSet<>();
        findings(analysis).forEach(f -> out.add(String.valueOf(f.get("code"))));
        return out;
    }

    private long rowRevision(UUID rule) { return sql.queryForObject("SELECT row_revision FROM rules WHERE rule_id=?", Long.class, rule); }

    @Test
    void p6_043_aTargetGovernedByAnExclusionGroupIsReportedWithTheRuleTheGroupAndItsMembers() {
        UUID target = device(), other = device();
        UUID heater = activeCommanding(target, true, 1, "Heater rule");
        UUID cooler = activeCommanding(other, true, 1, "Cooler rule");
        String groupName = "heater-vs-cooler-" + UUID.randomUUID();
        var group = groups.create(groupName);
        groups.setMember(group.groupId(), heater, 1);
        groups.setMember(group.groupId(), cooler, 2);

        Map<String, Object> analysis = analyzer.analyze(draftCommanding(target, false, 5));

        assertThat(analysis.get("status")).isEqualTo("CONFLICTS");
        Map<String, Object> governed = findings(analysis).stream().filter(f -> "MUTUAL_EXCLUSION_TARGET_GOVERNED".equals(f.get("code"))).findFirst().orElseThrow();
        assertThat(governed.get("ruleId")).isEqualTo(heater.toString());
        assertThat(governed.get("ruleName")).isEqualTo("Heater rule");
        assertThat(governed.get("exclusionGroupId")).isEqualTo(group.groupId().toString());
        assertThat(governed.get("exclusionGroupName")).isEqualTo(groupName);
        assertThat(governed.get("memberRank")).isEqualTo(1);
        assertThat(governed.get("otherGroupMembers").toString()).contains(cooler.toString()).contains("(active)");
        assertThat(governed.get("reason").toString()).contains("Heater rule").contains(heater.toString()).contains(groupName).contains("without being a member");
        // the arbitration relationship is reported too: the draft (5) loses to the heater rule (1) on the same target
        assertThat(codes(analysis)).contains("TARGET_DRAFT_WOULD_BE_SUPPRESSED");
    }

    @Test
    void p6_043_aDifferentTargetIsNotAConflictEvenWhenOtherRulesAreInGroups() {
        UUID target = device(), elsewhere = device();
        UUID heater = activeCommanding(target, true, 1, "Heater rule");
        var group = groups.create("grouped-" + UUID.randomUUID());
        groups.setMember(group.groupId(), heater, 1);

        Map<String, Object> analysis = analyzer.analyze(draftCommanding(elsewhere, true, 1));

        assertThat(analysis.get("status")).isEqualTo("CLEAR");
        assertThat(findings(analysis)).isEmpty();
    }

    @Test
    void p6_043_aSameTargetRuleWithoutAGroupGivesAnArbitrationFindingButNoExclusionFinding() {
        UUID target = device();
        activeCommanding(target, true, 5, "Ungrouped rule");

        Map<String, Object> analysis = analyzer.analyze(draftCommanding(target, false, 5));

        assertThat(analysis.get("status")).isEqualTo("CONFLICTS");
        assertThat(codes(analysis)).containsExactly("TARGET_CONFLICT_EQUAL_PRIORITY");
    }

    @Test
    void p6_043_priorityAndIdenticalCommandRelationshipsFollowTheArbiter() {
        UUID target = device();
        activeCommanding(target, true, 5, "Existing rule");
        assertThat(codes(analyzer.analyze(draftCommanding(target, false, 2)))).containsExactly("TARGET_DRAFT_WOULD_OVERRIDE");
        assertThat(codes(analyzer.analyze(draftCommanding(target, false, 9)))).containsExactly("TARGET_DRAFT_WOULD_BE_SUPPRESSED");
        Map<String, Object> identical = analyzer.analyze(draftCommanding(target, true, 9));
        assertThat(codes(identical)).containsExactly("TARGET_IDENTICAL_COMMAND");
        assertThat(identical.get("status")).as("coalesced identical commands are information, not a conflict").isEqualTo("CLEAR");
    }

    @Test
    void p6_043_severalOverlappingRulesInDifferentGroupsAreAllReportedByGroup() {
        UUID target = device();
        UUID a = activeCommanding(target, true, 1, "Rule A");
        UUID b = activeCommanding(target, true, 2, "Rule B");
        UUID c = activeCommanding(device(), true, 1, "Unrelated rule");
        var g1 = groups.create("group-one-" + UUID.randomUUID());
        var g2 = groups.create("group-two-" + UUID.randomUUID());
        groups.setMember(g1.groupId(), a, 1);
        groups.setMember(g2.groupId(), b, 1);
        groups.setMember(g2.groupId(), c, 2);

        Map<String, Object> analysis = analyzer.analyze(draftCommanding(target, false, 3));

        List<Map<String, Object>> governed = findings(analysis).stream().filter(f -> "MUTUAL_EXCLUSION_TARGET_GOVERNED".equals(f.get("code"))).toList();
        assertThat(governed).extracting(f -> f.get("exclusionGroupId")).containsExactlyInAnyOrder(g1.groupId().toString(), g2.groupId().toString());
        assertThat(governed).extracting(f -> f.get("ruleId")).containsExactlyInAnyOrder(a.toString(), b.toString());
        assertThat(governed.stream().filter(f -> f.get("exclusionGroupId").equals(g2.groupId().toString())).findFirst().orElseThrow().get("otherGroupMembers").toString()).contains(c.toString());
        assertThat(findings(analysis).stream().map(f -> String.valueOf(f.get("ruleId"))).toList()).doesNotContain(c.toString());
    }

    @Test
    void p6_043_pausedAndArchivedRulesAreIgnoredAndNamedAsIgnoredNeverAsConflicts() {
        UUID target = device();
        UUID paused = activeCommanding(target, true, 1, "Paused rule");
        UUID archived = activeCommanding(target, true, 1, "Archived rule");
        var group = groups.create("inactive-" + UUID.randomUUID());
        groups.setMember(group.groupId(), paused, 1);
        lifecycle.transition(paused, "pause", rowRevision(paused));
        lifecycle.transition(archived, "archive", rowRevision(archived));

        Map<String, Object> analysis = analyzer.analyze(draftCommanding(target, false, 1));

        assertThat(analysis.get("status")).isEqualTo("CLEAR");
        assertThat(findings(analysis)).isEmpty();
        @SuppressWarnings("unchecked") List<Map<String, Object>> ignored = (List<Map<String, Object>>) analysis.get("ignored");
        assertThat(ignored).extracting(i -> i.get("ruleId")).containsExactly(paused.toString());
        assertThat(ignored.get(0).get("lifecycle")).isEqualTo("PAUSED");
    }

    @Test
    void p6_043_aSupersededVersionDoesNotGovernTheTargetButTheCurrentOneDoes() {
        UUID oldTarget = device(), newTarget = device();
        UUID trigger = device();
        UUID rule = typedRule(trigger, "Edited rule", 1, TELEMETRY, List.of(temperatureAbove("40")), List.of(ledCommand(oldTarget, true)));
        var group = groups.create("edited-" + UUID.randomUUID());
        groups.setMember(group.groupId(), rule, 1);
        // edit the active rule to command another device: a new ACTIVE version supersedes the one that commanded oldTarget
        Rule current = lifecycle.legacySave(new Rule(rule, "Edited rule", true, 1, List.of(new com.nexus.rule.domain.RuleCondition(com.nexus.rule.domain.RuleField.DEVICE_ID, com.nexus.rule.domain.RuleOperator.EQ, trigger.toString()), temperatureAbove("40")),
                List.of(ledCommand(newTarget, true)), Map.of(), null, null, new com.nexus.rule.domain.RuleRuntime(TELEMETRY, null, null)), rowRevision(rule)).definition();
        assertThat(current.actions().get(0).parameters().get("deviceId")).isEqualTo(newTarget.toString());
        assertThat(count("SELECT count(*) FROM rule_versions WHERE rule_id=?", rule)).isGreaterThanOrEqualTo(2);

        assertThat(analyzer.analyze(draftCommanding(oldTarget, false, 1)).get("status")).as("the superseded version no longer commands this target").isEqualTo("CLEAR");
        Map<String, Object> now = analyzer.analyze(draftCommanding(newTarget, false, 1));
        assertThat(now.get("status")).isEqualTo("CONFLICTS");
        assertThat(codes(now)).contains("MUTUAL_EXCLUSION_TARGET_GOVERNED");
    }

    @Test
    void p6_043_onlyTheSharedTargetOfAMultiCommandRuleIsReportedAndUnrelatedRulesDoNotChangeTheSnapshot() {
        UUID shared = device(), unrelated = device();
        typedRule(device(), "Two-command rule", 1, TELEMETRY, List.of(temperatureAbove("40")), List.of(ledCommand(unrelated, true), ledCommand(shared, true)));
        Rule draft = draftCommanding(shared, false, 5);
        Map<String, Object> before = analyzer.analyze(draft);
        assertThat(findings(before)).hasSize(1);
        assertThat(findings(before).get(0).get("target").toString()).contains(shared.toString()).doesNotContain(unrelated.toString());
        activeCommanding(device(), true, 1, "Unrelated rule");
        assertThat(analyzer.analyze(draft).get("snapshotDigest")).as("a rule on another target is not part of the compared snapshot").isEqualTo(before.get("snapshotDigest"));
    }

    @Test
    void p6_043_aDraftWithoutACommandIsNotApplicableAndNeverClear() {
        Rule alertOnly = new Rule(UUID.randomUUID(), "Alert draft", false, 5, List.of(), List.of(alert()), Map.of(), 60, null, null);
        assertThat(analyzer.analyze(alertOnly).get("status")).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void p6_043_concurrentMembershipChangesNeverProduceAMixedSnapshot() throws Exception {
        UUID target = device();
        UUID rule = activeCommanding(target, true, 1, "Toggled rule");
        var group = groups.create("toggle-" + UUID.randomUUID());
        Rule draft = draftCommanding(target, false, 5);
        Set<String> withoutMember = new HashSet<>();
        withoutMember.add((String) analyzer.analyze(draft).get("snapshotDigest"));
        groups.setMember(group.groupId(), rule, 1);
        Set<String> withMember = new HashSet<>();
        withMember.add((String) analyzer.analyze(draft).get("snapshotDigest"));
        groups.removeMember(group.groupId(), rule);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> toggler = pool.submit((Callable<Void>) () -> {
                for (int i = 0; i < 40; i++) {
                    groups.setMember(group.groupId(), rule, 1);
                    groups.removeMember(group.groupId(), rule);
                }
                return null;
            });
            List<Map<String, Object>> seen = new ArrayList<>();
            while (!toggler.isDone()) seen.add(analyzer.analyze(draft));
            toggler.get();
            assertThat(seen).isNotEmpty();
            for (Map<String, Object> analysis : seen) {
                boolean governed = codes(analysis).contains("MUTUAL_EXCLUSION_TARGET_GOVERNED");
                String digest = (String) analysis.get("snapshotDigest");
                assertThat(governed ? withMember : withoutMember).as("one snapshot: findings and digest agree").contains(digest);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void p6_043_analysisWritesNothing() {
        UUID target = device();
        UUID rule = activeCommanding(target, true, 1, "Read-only subject");
        var group = groups.create("readonly-" + UUID.randomUUID());
        groups.setMember(group.groupId(), rule, 1);
        String before = state(rule);
        analyzer.analyze(draftCommanding(target, false, 1));
        analyzer.analyze(draftCommanding(device(), false, 1));
        assertThat(state(rule)).isEqualTo(before);
    }

    private String state(UUID rule) {
        return sql.queryForObject("""
                SELECT (SELECT count(*) FROM rules)||'/'||(SELECT count(*) FROM rule_versions)||'/'||(SELECT count(*) FROM rule_exclusion_members)||'/'||
                       (SELECT count(*) FROM ai_rule_drafts)||'/'||(SELECT count(*) FROM command_outbox)||'/'||(SELECT count(*) FROM action_history)||'/'||
                       (SELECT row_revision FROM rules WHERE rule_id=?)||'/'||(SELECT lifecycle FROM rules WHERE rule_id=?)""", String.class, rule, rule);
    }

    // ------------------------------------------------------------------ UNRESOLVED: never declared safe

    private static final class FakeFacts implements DraftConflictAnalyzer.ConflictFacts {
        List<DraftConflictAnalyzer.ActiveRule> active = List.of();
        boolean failActive, failGroups;

        @Override public List<DraftConflictAnalyzer.ActiveRule> activeRules() { if (failActive) throw new IllegalStateException("database down"); return active; }
        @Override public List<DraftConflictAnalyzer.Membership> groupsOf(Collection<UUID> ruleIds) { if (failGroups) throw new IllegalStateException("database down"); return List.of(); }
        @Override public List<DraftConflictAnalyzer.InactiveRule> nonActiveRules() { return List.of(); }
        @Override public String stateFingerprint(Collection<String> targetKeys, UUID excludedRule) { return "fake"; }
    }

    @Test
    void p6_043_whenTheComparisonCannotBeMadeTheResultIsUnresolvedNotClear() {
        UUID target = UUID.randomUUID();
        Rule draft = draftCommanding(target, true, 1);

        FakeFacts unreadable = new FakeFacts();
        unreadable.failActive = true;
        Map<String, Object> a = new DraftConflictAnalyzer(unreadable).compare(draft);
        assertThat(a.get("status")).isEqualTo("UNRESOLVED");
        assertThat(a.get("unresolvedCode")).isEqualTo("RULES_UNREADABLE");

        FakeFacts tooMany = new FakeFacts();
        Rule someRule = draftCommanding(UUID.randomUUID(), true, 1);
        tooMany.active = java.util.stream.IntStream.range(0, DraftConflictAnalyzer.MAX_ACTIVE_RULES + 1)
                .mapToObj(i -> new DraftConflictAnalyzer.ActiveRule(UUID.randomUUID(), UUID.randomUUID(), 1, someRule)).toList();
        assertThat(new DraftConflictAnalyzer(tooMany).compare(draft).get("unresolvedCode")).isEqualTo("TOO_MANY_ACTIVE_RULES");

        FakeFacts groupsDown = new FakeFacts();
        groupsDown.active = List.of(new DraftConflictAnalyzer.ActiveRule(UUID.randomUUID(), UUID.randomUUID(), 1, draftCommanding(target, true, 1)));
        groupsDown.failGroups = true;
        Map<String, Object> g = new DraftConflictAnalyzer(groupsDown).compare(draft);
        assertThat(g.get("status")).isEqualTo("UNRESOLVED");
        assertThat(g.get("unresolvedCode")).isEqualTo("EXCLUSION_GROUPS_UNREADABLE");

        Rule noCapability = new Rule(UUID.randomUUID(), "Broken", false, 1, List.of(), List.of(new RuleAction("SEND_COMMAND", Map.of("deviceId", target.toString(), "command", "SET_STATE"))), Map.of(), 60, null, null);
        assertThat(new DraftConflictAnalyzer(new FakeFacts()).compare(noCapability).get("unresolvedCode")).isEqualTo("TARGET_NOT_COMPUTABLE");

        assertThat(new DraftConflictAnalyzer(new FakeFacts()).compare(draft).get("status")).as("no rules, comparison made: CLEAR").isEqualTo("CLEAR");
    }

    @Test
    void p6_043_theDatabaseTargetKeysEqualTheArbitersTargetKeys() throws Exception {
        UUID d = UUID.randomUUID();
        List<RuleAction> actions = List.of(
                new RuleAction("SEND_COMMAND", Map.of("deviceId", d.toString().toUpperCase(), "capability", " Relay ", "command", "SET_STATE", "value", Map.of("channel", 2.0))),
                new RuleAction("SEND_COMMAND", Map.of("deviceId", d.toString(), "capabilityKey", "LED", "capability", "ignored", "command", "SET_STATE", "value", true)),
                new RuleAction("SEND_COMMAND", Map.of("deviceId", d.toString(), "capability", "FAN", "command", "SET_STATE", "value", Map.of("level", 3))),
                new RuleAction("CREATE_ALERT", Map.of("severity", "INFO")));
        Rule rule = new Rule(UUID.randomUUID(), "parity", false, 1, List.of(), actions, Map.of(), 60, null, null);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(rule);
        Set<String> expected = new java.util.TreeSet<>();
        for (RuleAction a : actions) { String k = com.nexus.rule.plan.ActionArbiter.targetKey(a); if (k != null) expected.add(k); }
        String[] fromDatabase = sql.queryForObject("SELECT ai_command_targets(?::jsonb)", (rs, n) -> (String[]) rs.getArray(1).getArray(), json);
        assertThat(expected).hasSize(3);
        assertThat(java.util.Arrays.asList(fromDatabase)).containsExactlyInAnyOrderElementsOf(expected);
    }
}
