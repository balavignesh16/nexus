package com.nexus.rule.plan;

import com.nexus.rule.domain.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.nexus.rule.plan.ActionDecision.Disposition.*;
import static org.junit.jupiter.api.Assertions.*;

/** C16 mutual exclusion: explicit group ranks, deterministic and fail closed. No winner comes from iteration order. */
class ActionArbiterExclusionTest {
    private static final UUID D1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID D2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID D3 = UUID.fromString("00000000-0000-0000-0000-0000000000d3");
    private static final UUID G1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID G2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID RA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID RB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID RC = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static RuleAction led(UUID device, boolean on) {
        return new RuleAction("SEND_COMMAND", Map.of("deviceId", device.toString(), "capability", "LED", "command", "SET_STATE", "value", on));
    }
    private static ActionCandidate candidate(UUID rule, int priority, int ordinal, RuleAction action) {
        var pinned = new Rule(rule, "r", true, priority, List.of(), List.of(action), Map.of(), null, new RuleExecutionIdentity(UUID.randomUUID(), 1, 1));
        return new ActionCandidate(UUID.randomUUID(), UUID.randomUUID(), pinned, ordinal, action, UUID.randomUUID(),
            ActionArbiter.targetKey(action), ActionArbiter.equivalenceKey(action), priority, "c");
    }
    private static Map<UUID, ActionDecision> byRule(List<ActionDecision> decisions) {
        var map = new HashMap<UUID, ActionDecision>();
        decisions.forEach(d -> map.put(d.candidate().rule().ruleId(), d));
        return map;
    }
    private static List<ExclusionMembership> group(UUID g, Object... ruleAndRank) {
        var list = new ArrayList<ExclusionMembership>();
        for (int i = 0; i < ruleAndRank.length; i += 2) list.add(new ExclusionMembership(g, (UUID) ruleAndRank[i], (Integer) ruleAndRank[i + 1]));
        return list;
    }

    @Test void theSmallerRankWinsAcrossDifferentDevicesAndTheOtherIsSuppressedWithTheWinnerNamed() {
        var a = candidate(RA, 5, 0, led(D1, true));
        var b = candidate(RB, 1, 0, led(D2, true));          // better PRIORITY, but exclusion is decided by the configured rank
        var d = byRule(ActionArbiter.arbitrate(List.of(a, b), group(G1, RA, 1, RB, 2)));
        assertEquals(SELECTED, d.get(RA).disposition());
        assertEquals(SUPPRESSED_EXCLUSION, d.get(RB).disposition());
        assertEquals("EXCLUSION_LOWER_RANK", d.get(RB).reason());
        assertEquals(a.candidateId(), d.get(RB).winnerCandidateId());
        assertTrue(d.get(RA).dispatches());
        assertFalse(d.get(RB).dispatches());
    }

    @Test void anEqualBestRankSuppressesEveryCompetingCommand() {
        var a = candidate(RA, 1, 0, led(D1, true));
        var b = candidate(RB, 1, 0, led(D2, true));
        var c = candidate(RC, 1, 0, led(D3, false));          // lower rank, but the tie already fails the group closed
        var d = byRule(ActionArbiter.arbitrate(List.of(a, b), group(G1, RA, 3, RB, 3)));
        assertEquals(SUPPRESSED_EXCLUSION_CONFLICT, d.get(RA).disposition());
        assertEquals(SUPPRESSED_EXCLUSION_CONFLICT, d.get(RB).disposition());
        var withThird = byRule(ActionArbiter.arbitrate(List.of(a, b, c), group(G1, RA, 3, RB, 3, RC, 9)));
        assertTrue(withThird.values().stream().noneMatch(ActionDecision::dispatches), "no random or first-come winner");
    }

    @Test void theOutcomeDoesNotDependOnTheOrderTheCandidatesArrive() {
        var a = candidate(RA, 2, 0, led(D1, true));
        var b = candidate(RB, 2, 0, led(D2, true));
        var c = candidate(RC, 2, 0, led(D3, true));
        var memberships = group(G1, RA, 2, RB, 1, RC, 5);
        var forward = ActionArbiter.arbitrate(List.of(a, b, c), memberships).stream().map(x -> x.candidate().rule().ruleId() + ":" + x.disposition()).sorted().toList();
        var reversed = ActionArbiter.arbitrate(List.of(c, b, a), group(G1, RC, 5, RB, 1, RA, 2)).stream().map(x -> x.candidate().rule().ruleId() + ":" + x.disposition()).sorted().toList();
        assertEquals(forward, reversed);
        assertEquals(List.of(RB.toString() + ":SELECTED"), forward.stream().filter(s -> s.endsWith(":SELECTED")).toList());
    }

    @Test void oneRulesOwnCommandsAreNeverExcludedFromEachOtherAndNonCommandActionsAreUnaffected() {
        var first = candidate(RA, 1, 0, led(D1, true));
        var second = candidate(RA, 1, 1, led(D2, true));
        var alert = candidate(RB, 1, 0, new RuleAction("CREATE_ALERT", Map.of("severity", "INFO")));
        var other = candidate(RB, 5, 1, led(D1, false));
        var decisions = ActionArbiter.arbitrate(List.of(first, second, alert, other), group(G1, RA, 1, RB, 2));
        var byId = new HashMap<UUID, ActionDecision>();
        decisions.forEach(x -> byId.put(x.candidate().candidateId(), x));
        assertEquals(SELECTED, byId.get(first.candidateId()).disposition());
        assertEquals(SELECTED, byId.get(second.candidateId()).disposition(), "same rule: not mutually exclusive with itself");
        assertEquals(SELECTED, byId.get(alert.candidateId()).disposition(), "an alert has no command target, exclusion never silences it");
        assertTrue(byId.get(other.candidateId()).disposition() == SUPPRESSED_PRIORITY || byId.get(other.candidateId()).disposition() == SUPPRESSED_EXCLUSION,
            "the loser's COMMAND is suppressed (here already by the same-target priority rule)");
    }

    @Test void aRuleOutsideEveryGroupIsUntouchedAndAGroupWithOneParticipantDoesNothing() {
        var a = candidate(RA, 1, 0, led(D1, true));
        var b = candidate(RB, 1, 0, led(D2, true));
        var d = byRule(ActionArbiter.arbitrate(List.of(a, b), group(G1, RA, 1)));
        assertEquals(SELECTED, d.get(RA).disposition());
        assertEquals(SELECTED, d.get(RB).disposition(), "RB is not a member: no restriction applies and none is invented");
    }

    @Test void whenTheWinningRulesCommandIsIdenticalToTheLoserTheCommandSurvivesWithAPromotedPrimary() {
        var lowRank = candidate(RB, 1, 0, led(D1, true));    // best priority -> coalescing primary, but loses the exclusion
        var highRank = candidate(RA, 2, 0, led(D1, true));   // identical command, wins the exclusion
        var d = byRule(ActionArbiter.arbitrate(List.of(lowRank, highRank), group(G1, RA, 1, RB, 2)));
        assertEquals(SUPPRESSED_EXCLUSION, d.get(RB).disposition());
        assertEquals(SELECTED, d.get(RA).disposition(), "the one command is still issued, by the rule that is allowed to act");
        assertEquals("PROMOTED_AFTER_EXCLUSION", d.get(RA).reason());
        assertEquals(1, d.values().stream().filter(ActionDecision::dispatches).count());
    }

    /**
     * REPLACES the former "a command suppressed by one group cannot win in the next" test. That cascade made the outcome depend on the
     * (random) group ids, which F2 identified as a defect: every group is now judged against the same initial candidate set.
     */
    @Test void severalGroupsAreJudgedAgainstTheSameInitialSetSoAnExcludedRuleCanStillExcludeInAnotherGroup() {
        var a = candidate(RA, 1, 0, led(D1, true));
        var b = candidate(RB, 1, 0, led(D2, true));
        var c = candidate(RC, 1, 0, led(D3, false));
        // G1: A beats B.  G2: B beats C. B is excluded by G1 but, judged against the initial set, still outranks C in G2 (conservative).
        var memberships = new ArrayList<ExclusionMembership>();
        memberships.addAll(group(G1, RA, 1, RB, 2));
        memberships.addAll(group(G2, RB, 1, RC, 2));
        var forward = byRule(ActionArbiter.arbitrate(List.of(a, b, c), memberships));
        var shuffled = byRule(ActionArbiter.arbitrate(List.of(c, a, b), memberships.reversed()));
        for (UUID rule : List.of(RA, RB, RC)) assertEquals(forward.get(rule).disposition(), shuffled.get(rule).disposition(), "deterministic for " + rule);
        assertEquals(SELECTED, forward.get(RA).disposition());
        assertEquals(SUPPRESSED_EXCLUSION, forward.get(RB).disposition());
        assertEquals(a.candidateId(), forward.get(RB).winnerCandidateId(), "B lost to A, who acts");
        assertEquals(SUPPRESSED_EXCLUSION, forward.get(RC).disposition());
        assertNull(forward.get(RC).winnerCandidateId(), "C lost to B, who does not act: no winner is named");
        assertEquals("EXCLUSION_LOWER_RANK_WINNER_EXCLUDED", forward.get(RC).reason());
    }

    // ------------------------------------------------------------------ F2: order independence and winner attribution

    private static final UUID RD = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final UUID RE = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    private static final UUID D4 = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final UUID D5 = UUID.fromString("00000000-0000-0000-0000-0000000000d5");

    /** disposition/reason plus the RULE (not the random candidate id) of the winner: comparable across runs. */
    private static Map<UUID, String> normalized(List<ActionDecision> decisions) {
        var byCandidate = new HashMap<UUID, ActionDecision>();
        decisions.forEach(d -> byCandidate.put(d.candidate().candidateId(), d));
        var out = new TreeMap<UUID, String>(Comparator.comparing(UUID::toString));
        for (var d : decisions) {
            var winner = d.winnerCandidateId() == null ? null : byCandidate.get(d.winnerCandidateId());
            out.put(d.candidate().rule().ruleId(), d.disposition() + "/" + d.reason() + "->" + (winner == null ? "-" : winner.candidate().rule().ruleId() + ":" + winner.disposition()));
        }
        return out;
    }
    /** Every winner pointer must name a FINAL SELECTED primary; nothing may name a suppressed, coalesced or missing row. */
    private static void assertTruthfulPointers(List<ActionDecision> decisions) {
        var byCandidate = new HashMap<UUID, ActionDecision>();
        decisions.forEach(d -> byCandidate.put(d.candidate().candidateId(), d));
        for (var d : decisions) {
            if (d.winnerCandidateId() == null) continue;
            var winner = byCandidate.get(d.winnerCandidateId());
            assertNotNull(winner, "dangling winner for " + d.reason());
            assertEquals(SELECTED, winner.disposition(), "winner of " + d.disposition() + "/" + d.reason() + " must be a selected primary");
            assertNotEquals(d.candidate().candidateId(), winner.candidate().candidateId(), "never its own winner");
        }
        for (var d : decisions)
            if (d.disposition() == SUPPRESSED_EXCLUSION || d.disposition() == SUPPRESSED_PRIORITY)
                assertTrue(d.winnerCandidateId() != null || d.reason().endsWith("WINNER_EXCLUDED"), "no winner needs the truthful reason, got " + d.reason());
        for (var d : decisions) if (d.disposition() == COALESCED) assertNotNull(d.winnerCandidateId(), "a contributor always names its primary");
    }
    private static <T> void permutations(List<T> items, List<List<T>> out, int n) {
        if (n == 1) { out.add(new ArrayList<>(items)); return; }
        for (int i = 0; i < n - 1; i++) {
            permutations(items, out, n - 1);
            Collections.swap(items, n % 2 == 0 ? i : 0, n - 1);
        }
        permutations(items, out, n - 1);
    }

    private record Scenario(List<ActionCandidate> candidates, List<List<Object>> groups) {}
    private static Scenario overlapping(boolean withTie) {
        var a = candidate(RA, 1, 0, led(D1, true));
        var b = candidate(RB, 1, 0, led(D2, true));
        var c = candidate(RC, 1, 0, led(D3, true));
        var d = candidate(RD, 1, 0, led(D4, true));
        var e = candidate(RE, 1, 0, led(D5, true));
        var groups = new ArrayList<List<Object>>();
        groups.add(List.of(RA, 1, RB, 2));                  // A beats B
        groups.add(List.of(RA, 5, RC, 1));                  // C beats A
        if (withTie) groups.add(List.of(RC, 3, RD, 3));     // C and D tie: both fail closed
        groups.add(List.of(RD, 1, RE, 2));                  // D beats E
        return new Scenario(List.of(a, b, c, d, e), groups);
    }
    private static List<ExclusionMembership> memberships(Scenario scenario, List<UUID> groupIds, List<Integer> groupOrder, boolean reverseMembers) {
        var list = new ArrayList<ExclusionMembership>();
        for (int position : groupOrder) {
            var spec = scenario.groups().get(position);
            var pairs = new ArrayList<ExclusionMembership>();
            for (int i = 0; i < spec.size(); i += 2) pairs.add(new ExclusionMembership(groupIds.get(position), (UUID) spec.get(i), (Integer) spec.get(i + 1)));
            if (reverseMembers) Collections.reverse(pairs);
            list.addAll(pairs);
        }
        return list;
    }

    private void assertOrderIndependent(boolean withTie, Map<UUID, String> expected) { assertOrderIndependent(overlapping(withTie), expected); }

    private void assertOrderIndependent(Scenario scenario, Map<UUID, String> expected) {
        int groupCount = scenario.groups().size();
        var idSets = new ArrayList<List<UUID>>();
        var rnd = new Random(7);
        for (int i = 0; i < 8; i++) {                                     // many different group id assignments, including sorted and reversed
            var ids = new ArrayList<UUID>();
            for (int g = 0; g < groupCount; g++) ids.add(new UUID(rnd.nextLong(), rnd.nextLong()));
            idSets.add(ids);
        }
        var sortedIds = new ArrayList<>(idSets.getFirst()); sortedIds.sort(Comparator.comparing(UUID::toString)); idSets.add(sortedIds);
        var reversedIds = new ArrayList<>(sortedIds); Collections.reverse(reversedIds); idSets.add(reversedIds);
        var candidateOrders = new ArrayList<List<ActionCandidate>>();
        permutations(new ArrayList<>(scenario.candidates()), candidateOrders, scenario.candidates().size());
        var groupOrders = new ArrayList<List<Integer>>();
        permutations(new ArrayList<>(java.util.stream.IntStream.range(0, groupCount).boxed().toList()), groupOrders, groupCount);
        int checked = 0;
        for (var ids : idSets) for (var order : candidateOrders) for (var go : groupOrders) for (boolean reverse : new boolean[]{false, true}) {
            var decisions = ActionArbiter.arbitrate(order, memberships(scenario, ids, go, reverse));
            assertEquals(expected, normalized(decisions));
            assertTruthfulPointers(decisions);
            checked++;
        }
        assertTrue(checked >= idSets.size() * candidateOrders.size() * groupOrders.size() * 2, "permutations checked: " + checked);
    }

    @Test void theDecisionsAndTheirEvidenceAreIdenticalForEveryGroupIdCandidateOrderAndMembershipOrder() {
        // Nobody acts: A, B and E lose to rules that are themselves excluded elsewhere; C and D tie in G3. Conservative and order-free.
        var expected = new TreeMap<UUID, String>(Comparator.comparing(UUID::toString));
        expected.put(RA, "SUPPRESSED_EXCLUSION/EXCLUSION_LOWER_RANK_WINNER_EXCLUDED->-");
        expected.put(RB, "SUPPRESSED_EXCLUSION/EXCLUSION_LOWER_RANK_WINNER_EXCLUDED->-");
        expected.put(RC, "SUPPRESSED_EXCLUSION_CONFLICT/EXCLUSION_EQUAL_RANK_CONFLICT->-");
        expected.put(RD, "SUPPRESSED_EXCLUSION_CONFLICT/EXCLUSION_EQUAL_RANK_CONFLICT->-");
        expected.put(RE, "SUPPRESSED_EXCLUSION/EXCLUSION_LOWER_RANK_WINNER_EXCLUDED->-");
        assertOrderIndependent(true, expected);
    }

    @Test void aWinnerThatStillActsIsNamedAndAWinnerExcludedElsewhereIsNever() {
        var expected = new TreeMap<UUID, String>(Comparator.comparing(UUID::toString));
        expected.put(RA, "SUPPRESSED_EXCLUSION/EXCLUSION_LOWER_RANK->" + RC + ":SELECTED");               // lost to C, who acts
        expected.put(RB, "SUPPRESSED_EXCLUSION/EXCLUSION_LOWER_RANK_WINNER_EXCLUDED->-");                  // lost to A, who is excluded by C
        expected.put(RC, "SELECTED/IDENTICAL_COMMAND_COALESCED->-");
        expected.put(RD, "SELECTED/IDENTICAL_COMMAND_COALESCED->-");
        expected.put(RE, "SUPPRESSED_EXCLUSION/EXCLUSION_LOWER_RANK->" + RD + ":SELECTED");
        assertOrderIndependent(false, expected);
    }

    @Test void aPriorityLoserNeverNamesAWinnerThatWasExcludedAndFollowsAPromotedContributor() {
        // A (rank 2, priority 1) beats B (no group, priority 2) on one target; C (rank 1) excludes A elsewhere.
        var a = candidate(RA, 1, 0, led(D1, true));
        var b = candidate(RB, 2, 0, led(D1, false));
        var c = candidate(RC, 1, 0, led(D2, true));
        var decisions = ActionArbiter.arbitrate(List.of(a, b, c), group(G1, RA, 2, RC, 1));
        assertTruthfulPointers(decisions);
        var byRule = byRule(decisions);
        assertEquals(SUPPRESSED_EXCLUSION, byRule.get(RA).disposition());
        assertEquals(SUPPRESSED_PRIORITY, byRule.get(RB).disposition());
        assertNull(byRule.get(RB).winnerCandidateId(), "its winner does not act, so no winner is named");
        assertEquals("LOWER_PRIORITY_CONFLICT_WINNER_EXCLUDED", byRule.get(RB).reason());
        assertFalse(byRule.get(RB).dispatches(), "and it stays suppressed: fail closed, no command for that target");

        // The excluded winner's class still has a surviving identical contributor: the loser then follows the promoted primary.
        var twin = candidate(RD, 3, 0, led(D1, true));                   // identical command, in no group
        var withTwin = ActionArbiter.arbitrate(List.of(a, b, c, twin), group(G1, RA, 2, RC, 1));
        assertTruthfulPointers(withTwin);
        var again = byRule(withTwin);
        assertEquals(SELECTED, again.get(RD).disposition());
        assertEquals("PROMOTED_AFTER_EXCLUSION", again.get(RD).reason());
        assertEquals(twin.candidateId(), again.get(RB).winnerCandidateId(), "the loser's winner pointer follows the promoted primary");
        assertEquals(SUPPRESSED_PRIORITY, again.get(RB).disposition());
    }

    @Test void coalescedContributorsStayDistinctFromSelectedPrimariesInEveryOutcome() {
        var primary = candidate(RA, 1, 0, led(D1, true));
        var contributor = candidate(RB, 2, 0, led(D1, true));
        var other = candidate(RC, 1, 0, led(D2, true));
        for (var decisions : List.of(ActionArbiter.arbitrate(List.of(primary, contributor, other), List.of()),
                                     ActionArbiter.arbitrate(List.of(other, contributor, primary), group(G1, RC, 1, RE, 2)))) {
            var byRule = byRule(decisions);
            assertEquals(SELECTED, byRule.get(RA).disposition());
            assertEquals(COALESCED, byRule.get(RB).disposition());
            assertEquals(primary.candidateId(), byRule.get(RB).winnerCandidateId());
            assertTruthfulPointers(decisions);
        }
    }

    @Test void aCandidateExcludedByTwoGroupsKeepsTheStrongestCauseWhateverTheGroupOrder() {
        // X loses to Y (rank 1) in one group and to Z (rank 2 is the group's best) in another: the smaller best rank, then the winner rule, decides.
        var x = candidate(RA, 1, 0, led(D1, true));
        var y = candidate(RB, 1, 0, led(D2, true));
        var z = candidate(RC, 1, 0, led(D3, true));
        var groups = new ArrayList<List<Object>>();
        groups.add(List.of(RA, 9, RC, 2));          // Z (rank 2) beats X
        groups.add(List.of(RA, 9, RB, 1));          // Y (rank 1) beats X
        var expected = new TreeMap<UUID, String>(Comparator.comparing(UUID::toString));
        expected.put(RA, "SUPPRESSED_EXCLUSION/EXCLUSION_LOWER_RANK->" + RB + ":SELECTED");
        expected.put(RB, "SELECTED/IDENTICAL_COMMAND_COALESCED->-");
        expected.put(RC, "SELECTED/IDENTICAL_COMMAND_COALESCED->-");
        assertOrderIndependent(new Scenario(List.of(x, y, z), groups), expected);

        // Adding an equal-rank conflict for X in a third group: the fail-closed cause outranks any lower-rank loss.
        var v = candidate(RD, 1, 0, led(D4, true));
        var withTie = new ArrayList<>(groups);
        withTie.add(List.of(RA, 4, RD, 4));
        var expectedTie = new TreeMap<UUID, String>(Comparator.comparing(UUID::toString));
        expectedTie.put(RA, "SUPPRESSED_EXCLUSION_CONFLICT/EXCLUSION_EQUAL_RANK_CONFLICT->-");
        expectedTie.put(RB, "SELECTED/IDENTICAL_COMMAND_COALESCED->-");
        expectedTie.put(RC, "SELECTED/IDENTICAL_COMMAND_COALESCED->-");
        expectedTie.put(RD, "SUPPRESSED_EXCLUSION_CONFLICT/EXCLUSION_EQUAL_RANK_CONFLICT->-");
        assertOrderIndependent(new Scenario(List.of(x, y, z, v), withTie), expectedTie);
    }
}
