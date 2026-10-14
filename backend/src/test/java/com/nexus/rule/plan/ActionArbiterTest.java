package com.nexus.rule.plan;

import com.nexus.rule.domain.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.nexus.rule.plan.ActionDecision.Disposition.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic C16 arbitration over one trigger batch. */
class ActionArbiterTest {
    private static final UUID DEVICE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private static RuleAction led(boolean on) {
        return new RuleAction("SEND_COMMAND", Map.of("deviceId", DEVICE.toString(), "capability", "LED", "command", "SET_STATE", "value", on));
    }
    private static RuleAction relay(int channel, boolean on) {
        return new RuleAction("SEND_COMMAND", Map.of("deviceId", DEVICE.toString(), "capabilityKey", "relay", "command", "SET_STATE",
            "value", Map.of("channel", channel, "state", on)));
    }
    private static ActionCandidate candidate(String ruleId, int priority, int ordinal, RuleAction action) {
        UUID rule = UUID.fromString(ruleId);
        var rule1 = new Rule(rule, "r", true, priority, List.of(), List.of(action), Map.of(), null, new RuleExecutionIdentity(UUID.randomUUID(), 1, 1));
        return new ActionCandidate(UUID.randomUUID(), UUID.randomUUID(), rule1, ordinal, action, UUID.randomUUID(),
            ActionArbiter.targetKey(action), ActionArbiter.equivalenceKey(action), priority, "c");
    }
    private static Map<UUID, ActionDecision> byCandidate(List<ActionDecision> decisions) {
        var map = new HashMap<UUID, ActionDecision>();
        decisions.forEach(d -> map.put(d.candidate().candidateId(), d));
        return map;
    }

    @Test void identicalCommandsCoalesceAndKeepEveryContributor() {
        var a = candidate("00000000-0000-0000-0000-00000000000a", 5, 0, led(true));
        var b = candidate("00000000-0000-0000-0000-00000000000b", 1, 0, led(true));
        var d = byCandidate(ActionArbiter.arbitrate(List.of(a, b)));
        assertEquals(SELECTED, d.get(b.candidateId()).disposition(), "the best-ordered contributor is the primary");
        assertEquals(COALESCED, d.get(a.candidateId()).disposition());
        assertEquals(b.candidateId(), d.get(a.candidateId()).winnerCandidateId());
        assertEquals(1, d.values().stream().filter(ActionDecision::dispatches).count());
    }

    @Test void smallerNumericPriorityWinsAConflict() {
        var low = candidate("00000000-0000-0000-0000-00000000000a", 9, 0, led(false));
        var high = candidate("00000000-0000-0000-0000-00000000000b", 2, 0, led(true));
        var d = byCandidate(ActionArbiter.arbitrate(List.of(low, high)));
        assertEquals(SELECTED, d.get(high.candidateId()).disposition());
        assertEquals(SUPPRESSED_PRIORITY, d.get(low.candidateId()).disposition());
        assertEquals(high.candidateId(), d.get(low.candidateId()).winnerCandidateId());
    }

    @Test void equalPriorityConflictSuppressesEveryConflictingCandidateWithoutRandomWinner() {
        var a = candidate("00000000-0000-0000-0000-00000000000a", 3, 0, led(true));
        var b = candidate("00000000-0000-0000-0000-00000000000b", 3, 0, led(false));
        var d = byCandidate(ActionArbiter.arbitrate(List.of(a, b)));
        assertEquals(SUPPRESSED_CONFLICT, d.get(a.candidateId()).disposition());
        assertEquals(SUPPRESSED_CONFLICT, d.get(b.candidateId()).disposition());
        assertTrue(d.values().stream().noneMatch(ActionDecision::dispatches));
    }

    @Test void differentTargetsAndRelayChannelsAreIndependent() {
        var ch1 = candidate("00000000-0000-0000-0000-00000000000a", 3, 0, relay(1, true));
        var ch2 = candidate("00000000-0000-0000-0000-00000000000b", 3, 0, relay(2, false));
        var alert = candidate("00000000-0000-0000-0000-00000000000c", 3, 0, new RuleAction("CREATE_ALERT", Map.of()));
        var d = byCandidate(ActionArbiter.arbitrate(List.of(ch1, ch2, alert)));
        assertTrue(d.values().stream().allMatch(ActionDecision::dispatches), "no shared target, so no conflict");
    }

    @Test void orderingIsStableRegardlessOfInputOrder() {
        var a = candidate("00000000-0000-0000-0000-00000000000a", 3, 0, led(true));
        var b = candidate("00000000-0000-0000-0000-00000000000b", 3, 1, led(true));
        var c = candidate("00000000-0000-0000-0000-00000000000c", 1, 0, led(true));
        var forward = ActionArbiter.arbitrate(List.of(a, b, c)).stream().map(x -> x.candidate().candidateId() + ":" + x.disposition()).toList();
        var shuffled = ActionArbiter.arbitrate(List.of(c, b, a)).stream().map(x -> x.candidate().candidateId() + ":" + x.disposition()).toList();
        assertEquals(new HashSet<>(forward), new HashSet<>(shuffled));
        assertEquals(c.candidateId(), ActionArbiter.arbitrate(List.of(b, a, c)).stream().filter(ActionDecision::dispatches).findFirst().orElseThrow().candidate().candidateId());
    }

    @Test void nonCommandActionsAreNeverSuppressedByCommandConflicts() {
        var stub = candidate("00000000-0000-0000-0000-00000000000a", 1, 0, new RuleAction("WEBHOOK", Map.of("url", "https://example.invalid")));
        var d = ActionArbiter.arbitrate(List.of(stub));
        assertEquals(SELECTED, d.getFirst().disposition());
        assertNull(stub.targetKey());
    }

    @Test void sameCapabilityOnDifferentDevicesIsIndependent() {
        UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
        var onFirst = candidate("00000000-0000-0000-0000-00000000000a", 3, 0, led(true));
        var onOther = candidate("00000000-0000-0000-0000-00000000000b", 3, 0,
            new RuleAction("SEND_COMMAND", Map.of("deviceId", other.toString(), "capability", "LED", "command", "SET_STATE", "value", false)));
        assertNotEquals(onFirst.targetKey(), onOther.targetKey(), "the canonical target includes the device");
        var d = byCandidate(ActionArbiter.arbitrate(List.of(onFirst, onOther)));
        assertTrue(d.values().stream().allMatch(ActionDecision::dispatches), "no global cross-device priority");
    }
}
