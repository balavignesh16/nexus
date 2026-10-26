package com.nexus.rule.trigger;

import com.nexus.action.application.ActionExecutionEngine;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionType;
import com.nexus.device.binding.DeviceCapabilityBinding;
import com.nexus.device.binding.DeviceCapabilityBindingRepository;
import com.nexus.device.capability.definition.CapabilityDefinitionRepository;
import com.nexus.rule.application.RuleCapabilityValidator;
import com.nexus.rule.application.RuleExecutionAuthorization;
import com.nexus.rule.application.RuleExecutionSuppressedException;
import com.nexus.rule.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Scenario 53: RELAY channels are separate command targets with their own owners, candidates, authorization and outcomes, resolved
 * against a REAL configured relay binding (channels [1,2]). Publication here is software publication to the disposable Mosquitto
 * broker: no ACK, convergence or physical actuation is claimed or simulated.
 */
class RuleRelayArbitrationIntegrationTest extends TypedTriggerSupport {
    @org.springframework.boot.test.mock.mockito.SpyBean ActionExecutionEngine engine;
    @Autowired DeviceCapabilityBindingRepository bindings;
    @Autowired CapabilityDefinitionRepository definitions;
    @Autowired RuleCapabilityValidator validator;
    @Autowired RuleExecutionAuthorization authorization;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.nexus.rule.persistence.RuleVersionStore versionStore;
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    /** A device with a genuinely configured, enabled RELAY binding offering channels 1 and 2. */
    private record Relay(UUID device, UUID binding) {}
    private Relay relay() {
        UUID target = device(), binding = UUID.randomUUID();
        var definition = definitions.findByKey("relay").orElseThrow();
        bindings.save(new DeviceCapabilityBinding(binding, target, definition, null, true, "BOOLEAN", Map.of("channels", List.of(1, 2)),
            Map.of("commandCapability", "RELAY"), Instant.now()));
        return new Relay(target, binding);
    }
    private static RuleAction channel(Relay relay, int channel, boolean on) {
        return new RuleAction("SEND_COMMAND", Map.of("deviceId", relay.device().toString(), "capabilityKey", "relay", "command", "SET_STATE",
            "value", Map.of("channel", channel, "state", on)));
    }
    /** Platform-wide rule: scope is the triggering device, the command targets the relay device. */
    private UUID relayRule(String name, int priority, RuleAction action) {
        UUID id = UUID.randomUUID();
        lifecycle.legacySave(new Rule(id, name, true, priority, List.of(temperatureAbove("10")), List.of(action), Map.of(), null, null, new RuleRuntime(TELEMETRY, null, null)), null);
        return id;
    }
    private Map<String, Object> candidate(UUID rule) {
        return sql.queryForMap("SELECT disposition,reason,request_id,outcome,target_key,winner_candidate_id,candidate_id FROM rule_action_candidates WHERE rule_id=? ORDER BY action_ordinal LIMIT 1", rule);
    }
    private Map<String, Object> desired(UUID device, String subtarget) {
        return sql.queryForMap("SELECT target_key,subtarget_key,command_id,value_type FROM current_desired_twin_state WHERE device_id=? AND subtarget_key=?", device, subtarget);
    }
    private Set<String> codes(Rule rule) { var set = new TreeSet<String>(); validator.inspect(rule).forEach(e -> set.add(e.get("code").toString())); return set; }

    @Test void twoConfiguredChannelsAreIndependentTargetsWithTheirOwnCandidatesOwnersAndOutcomes() {
        UUID trigger = device();
        Relay relay = relay();
        UUID one = relayRule("relay channel 1", 1, channel(relay, 1, true));
        UUID two = relayRule("relay channel 2", 2, channel(relay, 2, false));
        ingest(trigger, 25, now);
        assertEquals("SELECTED", candidate(one).get("disposition"));
        assertEquals("SELECTED", candidate(two).get("disposition"), "different channels are different targets: no conflict, whatever the priorities");
        assertNotEquals(candidate(one).get("target_key"), candidate(two).get("target_key"));
        assertTrue(candidate(one).get("target_key").toString().endsWith("|channel:1"));
        assertTrue(candidate(two).get("target_key").toString().endsWith("|channel:2"));
        assertEquals(2, count("SELECT count(*) FROM action_history WHERE device_id=? AND action_type='SEND_COMMAND'", relay.device()));
        var d1 = desired(relay.device(), "channel:1");
        var d2 = desired(relay.device(), "channel:2");
        assertEquals("binding:" + relay.binding(), d1.get("target_key"), "the command resolved to the installed binding identity");
        assertEquals("binding:" + relay.binding(), d2.get("target_key"));
        assertEquals(candidate(one).get("request_id"), d1.get("command_id"), "channel 1's desired state is owned by rule 1's request");
        assertEquals(candidate(two).get("request_id"), d2.get("command_id"), "channel 2's desired state is owned by rule 2's request");
        assertEquals("ACCEPTED", candidate(one).get("outcome"));
        assertEquals("ACCEPTED", candidate(two).get("outcome"));
        verifyPublications(2);
    }

    @Test void aConflictOnOneChannelNeverTouchesTheOtherChannel() {
        UUID trigger = device();
        Relay relay = relay();
        UUID on = relayRule("ch1 on", 1, channel(relay, 1, true));
        UUID off = relayRule("ch1 off", 3, channel(relay, 1, false));
        UUID other = relayRule("ch2 off", 2, channel(relay, 2, false));
        ingest(trigger, 25, now);
        assertEquals("SELECTED", candidate(on).get("disposition"));
        assertEquals("SUPPRESSED_PRIORITY", candidate(off).get("disposition"));
        assertEquals(candidate(on).get("candidate_id"), candidate(off).get("winner_candidate_id"));
        assertEquals("SELECTED", candidate(other).get("disposition"), "channel 2 is outside channel 1's conflict");
        assertEquals(2, count("SELECT count(*) FROM action_history WHERE device_id=? AND action_type='SEND_COMMAND'", relay.device()));
        assertEquals(candidate(on).get("request_id"), desired(relay.device(), "channel:1").get("command_id"));
        assertEquals(1, count("SELECT count(*) FROM current_desired_twin_state WHERE device_id=? AND subtarget_key='channel:1'", relay.device()));
    }

    @Test void equalPriorityOnTheSameChannelDispatchesNothingForThatChannelOnly() {
        UUID trigger = device();
        Relay relay = relay();
        UUID a = relayRule("tie on", 2, channel(relay, 1, true));
        UUID b = relayRule("tie off", 2, channel(relay, 1, false));
        UUID free = relayRule("free ch2", 2, channel(relay, 2, true));
        ingest(trigger, 25, now);
        assertEquals("SUPPRESSED_CONFLICT", candidate(a).get("disposition"));
        assertEquals("SUPPRESSED_CONFLICT", candidate(b).get("disposition"));
        assertEquals("SELECTED", candidate(free).get("disposition"));
        assertEquals(0, count("SELECT count(*) FROM current_desired_twin_state WHERE device_id=? AND subtarget_key='channel:1'", relay.device()));
        assertEquals(1, count("SELECT count(*) FROM current_desired_twin_state WHERE device_id=? AND subtarget_key='channel:2'", relay.device()));
    }

    @Test void onlyConfiguredChannelsAndValidCommandShapesAreAcceptedAtActivationTime() {
        Relay relay = relay();
        var conditions = List.of(temperatureAbove("10"));
        java.util.function.Function<RuleAction, Rule> rule = action -> new Rule(UUID.randomUUID(), "validated", true, 1, conditions, List.of(action), Map.of(), null, null, new RuleRuntime(TELEMETRY, null, null));
        assertEquals(Set.of(), codes(rule.apply(channel(relay, 1, true))));
        assertEquals(Set.of(), codes(rule.apply(channel(relay, 2, false))));
        assertTrue(codes(rule.apply(channel(relay, 3, true))).contains("INVALID_ACTION_CONTRACT"), "channel 3 is not configured on this binding");
        assertTrue(codes(rule.apply(channel(relay, 0, true))).contains("INVALID_ACTION_CONTRACT"));
        var badState = new RuleAction("SEND_COMMAND", Map.of("deviceId", relay.device().toString(), "capabilityKey", "relay", "command", "SET_STATE", "value", Map.of("channel", 1, "state", "on")));
        assertTrue(codes(rule.apply(badState)).contains("INVALID_ACTION_CONTRACT"), "state must be boolean");
        var noChannel = new RuleAction("SEND_COMMAND", Map.of("deviceId", relay.device().toString(), "capabilityKey", "relay", "command", "SET_STATE", "value", Map.of("state", true)));
        assertTrue(codes(rule.apply(noChannel)).contains("INVALID_ACTION_CONTRACT"));
        var unknownCommand = new RuleAction("SEND_COMMAND", Map.of("deviceId", relay.device().toString(), "capabilityKey", "relay", "command", "TOGGLE_ALL", "value", Map.of("channel", 1, "state", true)));
        assertTrue(codes(rule.apply(unknownCommand)).contains("INVALID_ACTION_CONTRACT"));
        UUID plainDevice = device();
        var notRelay = new RuleAction("SEND_COMMAND", Map.of("deviceId", plainDevice.toString(), "capabilityKey", "relay", "command", "SET_STATE", "value", Map.of("channel", 1, "state", true)));
        assertTrue(codes(rule.apply(notRelay)).contains("INVALID_ACTION_CONTRACT"), "a device without a configured relay binding offers no channel at all");
    }

    @Test void aRequestPlannedForOneChannelCannotBeReplayedAgainstTheOther() {
        UUID trigger = device();
        Relay relay = relay();
        UUID one = relayRule("channel 1 only", 1, channel(relay, 1, true));
        // Plan without dispatching: the persisted candidate exists, nothing was accepted.
        doNothing().when(engine).process(any(), any(), any());
        try { ingest(trigger, 25, now); } finally { reset(engine); }
        assertEquals(0, count("SELECT count(*) FROM action_history WHERE device_id=?", relay.device()));
        UUID matchId = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, one);
        UUID requestId = (UUID) candidate(one).get("request_id");
        var owner = sql.queryForMap("SELECT rule_version_id,rule_revision,execution_epoch FROM rule_matches WHERE match_id=?", matchId);
        var definition = versionStore.version(one, (UUID) owner.get("rule_version_id"));
        var pinned = new Rule(one, definition.name(), true, 1, definition.conditions(), definition.actions(), Map.of(), null,
            new RuleExecutionIdentity((UUID) owner.get("rule_version_id"), ((Number) owner.get("rule_revision")).longValue(), ((Number) owner.get("execution_epoch")).longValue()), definition.runtime());
        var wrongChannel = new LinkedHashMap<>(channel(relay, 2, true).parameters());
        var denied = assertThrows(RuleExecutionSuppressedException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(
            tx -> authorization.check(new ActionExecutionRequest(requestId, matchId, ActionType.SEND_COMMAND, wrongChannel), new ActionContext(null, pinned))));
        assertEquals("CANDIDATE_REQUEST_MISMATCH", denied.getMessage(), "channel 1's authority does not cover channel 2");
        assertDoesNotThrow(() -> new TransactionTemplate(transactions).executeWithoutResult(
            tx -> authorization.check(new ActionExecutionRequest(requestId, matchId, ActionType.SEND_COMMAND, new LinkedHashMap<>(channel(relay, 1, true).parameters())), new ActionContext(null, pinned))));
    }

}
