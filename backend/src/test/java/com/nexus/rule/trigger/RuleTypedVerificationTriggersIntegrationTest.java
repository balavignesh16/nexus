package com.nexus.rule.trigger;

import com.nexus.action.application.CommandAcceptanceService;
import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionType;
import com.nexus.device.binding.DeviceCapabilityBinding;
import com.nexus.device.binding.DeviceCapabilityBindingRepository;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.definition.*;
import com.nexus.rule.domain.*;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.twin.application.ReadbackMappingService;
import com.nexus.twin.application.VerificationService;
import com.nexus.twin.domain.VerificationStateId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

/** VERIFICATION_CHANGED (status filters) and its TIMED_OUT form from committed CONVERGENCE_CHANGED transitions. */
class RuleTypedVerificationTriggersIntegrationTest extends TypedTriggerSupport {
    @Autowired CommandAcceptanceService acceptance;
    @Autowired DeviceCapabilityBindingRepository bindings;
    @Autowired CapabilityDefinitionRepository definitions;
    @Autowired ReadbackMappingService mappings;
    @Autowired VerificationService verification;
    @Autowired com.nexus.device.command.DeviceCommandAckMessageHandler acks;

    record Fixture(UUID device, DeviceCapabilityBinding write, CapabilityDefinition writeDefinition, CapabilityDefinition read) {}

    private Fixture fixture() {
        UUID device = device();
        var write = definitions.findByKey("led").orElseThrow();
        var read = definitions.save(new CapabilityDefinition(UUID.randomUUID(), "led_readback_" + UUID.randomUUID().toString().replace("-", ""),
            "LED readback", null, CapabilityDirection.READ, CapabilityValueType.BOOLEAN, "BOOLEAN",
            List.of("BOOLEAN"), null, null, List.of(), List.of(), "actuator", Map.of(), Map.of()));
        Instant created = Instant.now();
        var writeBinding = bindings.save(new DeviceCapabilityBinding(UUID.randomUUID(), device, write, null, true, "BOOLEAN",
            Map.of(), Map.of("commandCapability", "LED"), created));
        var readBinding = bindings.save(new DeviceCapabilityBinding(UUID.randomUUID(), device, read, null, true, "BOOLEAN",
            Map.of(), Map.of("telemetryKey", "LED_READBACK"), created));
        mappings.addDevice(device, new ReadbackMappingService.Policy(writeBinding.getId(), "", readBinding.getId(),
            "IDENTITY", null, "EXACT", null, "COMMAND_ECHO", 10));
        return new Fixture(device, writeBinding, write, read);
    }

    private UUID accept(Fixture f) {
        UUID command = UUID.randomUUID();
        var request = new ActionExecutionRequest(command, null, ActionType.SEND_COMMAND,
            Map.of("deviceId", f.device().toString(), "capabilityKey", "led", "command", "SET_STATE", "value", true));
        doReturn(new CommandExecutor.PreparedCommand(f.device(), f.write().getId(), null, f.writeDefinition().getId(), "led",
            CapabilityValueType.BOOLEAN, "BOOLEAN", "", "LED", "SET_STATE", true)).when(executor).prepare(eq(request));
        acceptance.accept(request, new ActionContext(null, null), Instant.now());
        return command;
    }

    private UUID rule(Fixture f, List<TriggerSubscription> triggers) {
        return typedRule(f.device(), "verification " + triggers, 1, triggers, List.of(), List.of(alert()));
    }

    @Test void convergedAndTimedOutTransitionsAreDistinctDurableFacts() {
        Fixture f = fixture();
        UUID converged = rule(f, List.of(new TriggerSubscription(TriggerType.VERIFICATION_CHANGED, Map.of("status", "CONVERGED"))));
        UUID timedOutNamed = rule(f, List.of(new TriggerSubscription(TriggerType.VERIFICATION_TIMED_OUT)));
        UUID timedOutFiltered = rule(f, List.of(new TriggerSubscription(TriggerType.VERIFICATION_CHANGED, Map.of("status", "TIMED_OUT"))));
        UUID both = rule(f, List.of(new TriggerSubscription(TriggerType.VERIFICATION_CHANGED), new TriggerSubscription(TriggerType.VERIFICATION_TIMED_OUT)));

        // 1. A command is accepted (PENDING) and then converges on a qualified mapped echo.
        UUID first = accept(f);
        assertEquals(1, facts("VERIFICATION_CHANGED", f.device()), "UNVERIFIED/none -> PENDING is the first committed transition");
        now = Instant.now().plusSeconds(2);
        telemetry.processTelemetry(new TelemetryRequest(f.device(), now.minusSeconds(1), "LED_READBACK", true, "BOOLEAN", f.read().getKey(), first));
        assertEquals(2, facts("VERIFICATION_CHANGED", f.device()));
        assertEquals(1, matches(converged), "status filter selects CONVERGED only");
        assertEquals(0, matches(timedOutNamed));

        // 2. A second command never receives a qualified echo and times out at its stored deadline.
        UUID second = accept(f);
        var id = new VerificationStateId(f.device(), "binding:" + f.write().getId(), "");
        Instant deadline = sql.queryForObject("SELECT verification_deadline_at FROM current_twin_verification_state WHERE device_id=?", java.sql.Timestamp.class, f.device()).toInstant();
        now = deadline.minusMillis(1);
        verification.deadline(id);
        assertEquals("PENDING", sql.queryForObject("SELECT verification_status FROM current_twin_verification_state WHERE device_id=?", String.class, f.device()));
        now = deadline;
        verification.deadline(id);
        assertEquals("TIMED_OUT", sql.queryForObject("SELECT verification_status FROM current_twin_verification_state WHERE device_id=?", String.class, f.device()));
        verification.deadline(id);                                // concurrent/duplicate processing
        String transition = sql.queryForObject("SELECT transition_id::text FROM twin_state_transitions WHERE verification_command_id=? AND new_verification_status='TIMED_OUT'", String.class, second);
        assertEquals(1, count("SELECT count(*) FROM rule_trigger_facts WHERE trigger_type='VERIFICATION_CHANGED' AND source_key=?", transition));
        assertEquals(0, count("SELECT count(*) FROM rule_trigger_facts WHERE trigger_type='VERIFICATION_TIMED_OUT'"), "the timed-out form is a filter over the same transition identity");
        assertEquals(1, matches(timedOutNamed));
        assertEquals(1, matches(timedOutFiltered));
        assertEquals(1, count("SELECT count(*) FROM rule_matches WHERE rule_id=? AND trigger_fact_id=(SELECT fact_id FROM rule_trigger_facts WHERE source_key=?)", both, transition),
            "a rule subscribed to both forms is decided once for one transition");
        assertEquals(1, matches(converged), "the earlier CONVERGED rule is not retriggered by the timeout");
    }

    @Test void aQualifiedMismatchingEchoDivergesAtTheDeadlineAndAnAckAloneNeverConverges() {
        Fixture f = fixture();
        UUID diverged = rule(f, List.of(new TriggerSubscription(TriggerType.VERIFICATION_CHANGED, Map.of("status", "DIVERGED"))));
        UUID converged = rule(f, List.of(new TriggerSubscription(TriggerType.VERIFICATION_CHANGED, Map.of("status", "CONVERGED"))));
        UUID command = accept(f);
        sql.update("UPDATE action_history SET status='SUCCESS', published_at=now(), ack_deadline_at=now()+interval '30 seconds' WHERE request_id=?", command);
        acks.handle("nexus/devices/" + f.device() + "/commands/ack", ("{\"commandId\":\"" + command + "\",\"status\":\"ACCEPTED\",\"timestamp\":" + Instant.now().getEpochSecond() + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0, matches(converged), "publication and a software ACK are not convergence");
        now = Instant.now().plusSeconds(2);
        telemetry.processTelemetry(new TelemetryRequest(f.device(), now.minusSeconds(1), "LED_READBACK", false, "BOOLEAN", f.read().getKey(), command));
        assertEquals(0, matches(diverged), "a mismatch before the deadline stays PENDING");
        Instant deadline = sql.queryForObject("SELECT verification_deadline_at FROM current_twin_verification_state WHERE device_id=?", java.sql.Timestamp.class, f.device()).toInstant();
        now = deadline;
        verification.deadline(new VerificationStateId(f.device(), "binding:" + f.write().getId(), ""));
        assertEquals("DIVERGED", sql.queryForObject("SELECT verification_status FROM current_twin_verification_state WHERE device_id=?", String.class, f.device()));
        assertEquals(1, matches(diverged));
        assertEquals(0, matches(converged));
    }

    @Autowired RuleManualService manual;

    @Test void ruleToCommandToAckToEchoToConvergenceKeepsEveryFactSeparate() {
        Fixture f = fixture();
        var action = new RuleAction("SEND_COMMAND", Map.of("deviceId", f.device().toString(), "capabilityKey", "led", "command", "SET_STATE", "value", true));
        UUID rule = typedRule(f.device(), "manual led", 1, List.of(new TriggerSubscription(TriggerType.MANUAL)), List.of(), List.of(action));
        UUID converged = rule(f, List.of(new TriggerSubscription(TriggerType.VERIFICATION_CHANGED, Map.of("status", "CONVERGED"))));

        manual.request(rule, null);                                  // authorized plan -> durable acceptance -> real Mosquitto publication
        UUID command = sql.queryForObject("SELECT request_id FROM action_history WHERE device_id=?", UUID.class, f.device());
        assertEquals("SUCCESS", sql.queryForObject("SELECT status FROM action_history WHERE request_id=?", String.class, command), "MQTT publication");
        assertNull(sql.queryForObject("SELECT ack_status FROM action_history WHERE request_id=?", String.class, command));
        assertEquals("PENDING", sql.queryForObject("SELECT verification_status FROM current_twin_verification_state WHERE device_id=?", String.class, f.device()));
        assertEquals(0, matches(converged));

        acks.handle("nexus/devices/" + f.device() + "/commands/ack", ("{\"commandId\":\"" + command + "\",\"status\":\"ACCEPTED\",\"timestamp\":" + Instant.now().getEpochSecond() + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("ACCEPTED", sql.queryForObject("SELECT ack_status FROM action_history WHERE request_id=?", String.class, command), "software ACK");
        assertEquals("PENDING", sql.queryForObject("SELECT verification_status FROM current_twin_verification_state WHERE device_id=?", String.class, f.device()), "an ACK never converges");

        now = Instant.now().plusSeconds(2);
        telemetry.processTelemetry(new TelemetryRequest(f.device(), now.minusSeconds(1), "LED_READBACK", true, "BOOLEAN", f.read().getKey(), command));
        assertEquals("CONVERGED", sql.queryForObject("SELECT verification_status FROM current_twin_verification_state WHERE device_id=?", String.class, f.device()), "qualified mapped echo");
        assertEquals(1, matches(converged));
        assertEquals(1, count("SELECT count(*) FROM rule_action_candidates c WHERE c.rule_id=? AND c.disposition='SELECTED' AND c.request_id=?", rule, command));
    }
}
