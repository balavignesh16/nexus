package com.nexus.rule.trigger;

import com.nexus.action.application.CommandDispatchCoordinator;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionExecutionResult;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.device.command.DeviceCommandAckMessageHandler;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.rule.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** CONNECTIVITY_CHANGED, COMMAND_PUBLICATION_FAILED, COMMAND_ACK_REJECTED and COMMAND_ACK_TIMED_OUT sources. */
class RuleTypedCommandAndConnectivityTriggersIntegrationTest extends TypedTriggerSupport {
    @Autowired CommandDispatchCoordinator dispatch;
    @Autowired DeviceCommandAckMessageHandler acks;
    @Autowired RuleAckTimeoutScanner timeouts;
    @org.springframework.boot.test.mock.mockito.SpyBean RuleSourceFacts sourceFacts;

    private UUID subscribed(UUID device, TriggerType type, Map<String, String> filters) {
        return typedRule(device, type + " rule", 1, List.of(new TriggerSubscription(type, filters)), List.of(), List.of(alert()));
    }

    private ActionExecutionRequest manualCommand(UUID device) {
        return new ActionExecutionRequest(UUID.randomUUID(), null, ActionType.SEND_COMMAND,
            Map.of("deviceId", device.toString(), "capability", "LED", "command", "SET_STATE", "value", true));
    }

    private void ack(UUID device, UUID command, String status, long epochSeconds, String reason) {
        String payload = "{\"commandId\":\"" + command + "\",\"status\":\"" + status + "\",\"timestamp\":" + epochSeconds + ",\"reason\":\"" + reason + "\"}";
        acks.handle("nexus/devices/" + device + "/commands/ack", payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void connectivityTransitionsArePersistedGenerationsNotCacheEvents() {
        UUID device = device();                                   // first ONLINE observation = generation 1, before any subscription exists
        assertEquals(0, facts("CONNECTIVITY_CHANGED", device), "no active version needs the fact, so none is written");
        UUID any = subscribed(device, TriggerType.CONNECTIVITY_CHANGED, Map.of());
        UUID offlineOnly = subscribed(device, TriggerType.CONNECTIVITY_CHANGED, Map.of("connectivity", "OFFLINE"));
        assertFalse(connectivity.applyObservedStatus(device, DeviceConnectivityStatus.ONLINE), "duplicate observation");
        assertEquals(0, facts("CONNECTIVITY_CHANGED", device));
        assertTrue(connectivity.applyObservedStatus(device, DeviceConnectivityStatus.OFFLINE));
        assertTrue(connectivity.applyObservedStatus(device, DeviceConnectivityStatus.ONLINE));
        assertEquals(2, facts("CONNECTIVITY_CHANGED", device));
        assertEquals(3L, sql.queryForObject("SELECT generation FROM device_connectivity WHERE device_id=?", Long.class, device));
        assertEquals(2, matches(any));
        assertEquals(1, matches(offlineOnly), "the OFFLINE filter ignores the ONLINE transition");
        assertEquals(3, count("SELECT count(*) FROM action_history WHERE match_id IN (SELECT match_id FROM rule_matches WHERE rule_id IN (?,?))", any, offlineOnly),
            "every selected candidate was actually executed and recorded (non-null correlation)");
        assertEquals(0, count("SELECT count(*) FROM action_history WHERE status<>'SUCCESS' AND match_id IN (SELECT match_id FROM rule_matches WHERE rule_id IN (?,?))", any, offlineOnly));
        assertEquals(0, count("SELECT count(*) FROM rule_matches WHERE rule_id IN (?,?) AND event_id IS NOT NULL", any, offlineOnly));
    }

    @Test void theCommittedRowDecidesTransitionsEvenWhenTheCacheIsStale() {
        UUID device = device();
        // Another instance (or manual repair) moved the committed row to OFFLINE; this instance's cache still says ONLINE.
        sql.update("UPDATE device_connectivity SET status='OFFLINE', generation=generation+1 WHERE device_id=?", device);
        assertTrue(connectivity.applyObservedStatus(device, DeviceConnectivityStatus.ONLINE), "ONLINE differs from the committed OFFLINE row");
        assertEquals(3L, sql.queryForObject("SELECT generation FROM device_connectivity WHERE device_id=?", Long.class, device));
        assertEquals(0, facts("CONNECTIVITY_CHANGED", device), "no subscription existed, so neither generation produced a fact");
        UUID rule = subscribed(device, TriggerType.CONNECTIVITY_CHANGED, Map.of());
        sql.update("UPDATE device_connectivity SET status='OFFLINE', generation=generation+1 WHERE device_id=?", device);
        assertTrue(connectivity.applyObservedStatus(device, DeviceConnectivityStatus.ONLINE), "the committed OFFLINE row, not the cache, decides again");
        assertEquals(1, facts("CONNECTIVITY_CHANGED", device), "generation 5 only; the out-of-band generation 4 has no fact");
        assertEquals(1, matches(rule));
        assertFalse(connectivity.applyObservedStatus(device, DeviceConnectivityStatus.ONLINE));
    }

    @Test void publicationFailureFiresOnlyAfterDurableAcceptance() {
        UUID device = device();
        UUID rule = subscribed(device, TriggerType.COMMAND_PUBLICATION_FAILED, Map.of());
        // A command rejected before acceptance is a validation failure, never a publication failure.
        var invalid = new ActionExecutionRequest(UUID.randomUUID(), null, ActionType.SEND_COMMAND,
            Map.of("deviceId", device.toString(), "capability", "LED", "command", "SET_STATE", "value", "not-a-boolean"));
        assertFalse(dispatch.dispatch(invalid, new ActionContext(null, null), "corr").accepted());
        assertEquals(0, facts("COMMAND_PUBLICATION_FAILED", device));
        assertEquals(0, matches(rule));

        var request = manualCommand(device);
        doAnswer(inv -> new ActionExecutionResult(request.requestId(), request, ExecutionStatus.FAILURE, Instant.now(), "broker unavailable"))
            .when(executor).publishPrepared(any(), any());
        try {
            assertTrue(dispatch.dispatch(request, new ActionContext(null, null), "corr").accepted());
        } finally { reset(executor); }
        assertEquals(1, facts("COMMAND_PUBLICATION_FAILED", device));
        assertEquals(1, count("SELECT count(*) FROM rule_trigger_facts WHERE trigger_type='COMMAND_PUBLICATION_FAILED' AND source_key=?", request.requestId().toString()));
        assertEquals(1, matches(rule));
        assertEquals(0, count("SELECT count(*) FROM rule_trigger_facts WHERE source_key=? AND trigger_type<>'COMMAND_PUBLICATION_FAILED'", request.requestId().toString()),
            "publication failure is not an ACK or verification fact");
    }

    @Test void firstAuthoritativeRejectedAckIsTheOnlyAckRejectedFact() {
        UUID device = device();
        UUID other = device();
        UUID rule = subscribed(device, TriggerType.COMMAND_ACK_REJECTED, Map.of());
        var request = manualCommand(device);
        assertTrue(dispatch.dispatch(request, new ActionContext(null, null), "corr").accepted());
        long ackTime = now.getEpochSecond();

        ack(other, request.requestId(), "REJECTED", ackTime, "wrong device");       // belongs to another device
        ack(device, UUID.randomUUID(), "REJECTED", ackTime, "unknown command");     // unknown command
        assertEquals(0, facts("COMMAND_ACK_REJECTED", device));

        ack(device, request.requestId(), "REJECTED", ackTime, "busy");
        assertEquals(1, facts("COMMAND_ACK_REJECTED", device));
        ack(device, request.requestId(), "REJECTED", ackTime, "busy");              // identical duplicate
        ack(device, request.requestId(), "ACCEPTED", ackTime + 1, "late conflicting"); // conflicting duplicate
        assertEquals(1, facts("COMMAND_ACK_REJECTED", device));
        assertEquals(1, matches(rule));
        assertEquals("REJECTED", sql.queryForObject("SELECT ack_status FROM action_history WHERE request_id=?", String.class, request.requestId()), "first ACK is preserved");
    }

    @Test void ackTimeoutIsStrictlyAfterTheDeadlineAndObservedOnce() {
        UUID device = device();
        UUID rule = subscribed(device, TriggerType.COMMAND_ACK_TIMED_OUT, Map.of());
        var request = manualCommand(device);
        assertTrue(dispatch.dispatch(request, new ActionContext(null, null), "corr").accepted());
        Instant deadline = sql.queryForObject("SELECT ack_deadline_at FROM action_history WHERE request_id=?", java.sql.Timestamp.class, request.requestId()).toInstant();
        assertNotNull(deadline);

        now = deadline.minusMillis(1);
        assertEquals(0, timeouts.scan(100));
        now = deadline;                                           // equality is not a timeout
        assertEquals(0, timeouts.scan(100));
        now = deadline.plusMillis(1);
        assertEquals(1, timeouts.scan(100));
        assertEquals(0, timeouts.scan(100), "observed once");
        assertEquals(1, facts("COMMAND_ACK_TIMED_OUT", device));
        assertEquals(1, matches(rule));
        verify(executor, times(1)).publishPrepared(any(), any());   // no resend of the command
    }

    @Test void anAcknowledgedCommandNeverTimesOut() {
        UUID device = device();
        var request = manualCommand(device);
        assertTrue(dispatch.dispatch(request, new ActionContext(null, null), "corr").accepted());
        ack(device, request.requestId(), "ACCEPTED", now.getEpochSecond(), "ok");
        now = now.plusSeconds(3600);
        assertEquals(0, timeouts.scan(100));
        assertEquals(0, facts("COMMAND_ACK_TIMED_OUT", device));
    }

    @Test void connectivityFactFailureRollsBackTheTransitionAndTheCache() {
        UUID device = device();
        UUID rule = subscribed(device, TriggerType.CONNECTIVITY_CHANGED, Map.of());
        long generation = sql.queryForObject("SELECT generation FROM device_connectivity WHERE device_id=?", Long.class, device);
        doThrow(new IllegalStateException("injected fact outage")).when(sourceFacts).connectivityChanged(any(), anyLong(), any(), any(), any());
        try {
            assertThrows(RuntimeException.class, () -> connectivity.applyObservedStatus(device, DeviceConnectivityStatus.OFFLINE));
        } finally { reset(sourceFacts); }
        assertEquals("ONLINE", sql.queryForObject("SELECT status FROM device_connectivity WHERE device_id=?", String.class, device));
        assertEquals(generation, sql.queryForObject("SELECT generation FROM device_connectivity WHERE device_id=?", Long.class, device));
        assertEquals(DeviceConnectivityStatus.ONLINE, connectivity.get(device).orElseThrow().status(), "the cache was not advanced past a rolled-back transition");
        assertEquals(0, matches(rule));
    }

    @Test void publicationFactFailureLeavesTheP24LifecycleIntactAndFiresNoRule() {
        UUID device = device();
        UUID rule = subscribed(device, TriggerType.COMMAND_PUBLICATION_FAILED, Map.of());
        var request = manualCommand(device);
        doAnswer(inv -> new ActionExecutionResult(request.requestId(), request, ExecutionStatus.FAILURE, Instant.now(), "broker unavailable"))
            .when(executor).publishPrepared(any(), any());
        doThrow(new IllegalStateException("injected fact outage")).when(sourceFacts).publicationFailed(any(), any(), any(), any());
        try {
            assertTrue(dispatch.dispatch(request, new ActionContext(null, null), "corr").accepted());
        } finally { reset(executor); reset(sourceFacts); }
        assertEquals("FAILURE", sql.queryForObject("SELECT status FROM action_history WHERE request_id=?", String.class, request.requestId()),
            "the command's publication failure is recorded regardless of Rule storage");
        assertEquals(0, facts("COMMAND_PUBLICATION_FAILED", device));
        assertEquals(0, matches(rule));
    }

    @Test void aLateAckAfterAnObservedTimeoutIsStoredWithoutRewritingTheTimeoutFact() {
        UUID device = device();
        UUID rule = subscribed(device, TriggerType.COMMAND_ACK_TIMED_OUT, Map.of());
        var request = manualCommand(device);
        assertTrue(dispatch.dispatch(request, new ActionContext(null, null), "corr").accepted());
        Instant deadline = sql.queryForObject("SELECT ack_deadline_at FROM action_history WHERE request_id=?", java.sql.Timestamp.class, request.requestId()).toInstant();
        now = deadline.plusSeconds(1);
        assertEquals(1, timeouts.scan(100));
        String factBefore = sql.queryForObject("SELECT occurred_at::text||source_key FROM rule_trigger_facts WHERE trigger_type='COMMAND_ACK_TIMED_OUT' AND source_key=?", String.class, request.requestId().toString());
        ack(device, request.requestId(), "ACCEPTED", now.getEpochSecond(), "late");
        assertEquals("ACCEPTED", sql.queryForObject("SELECT ack_status FROM action_history WHERE request_id=?", String.class, request.requestId()), "late ACK evidence is stored");
        assertEquals(factBefore, sql.queryForObject("SELECT occurred_at::text||source_key FROM rule_trigger_facts WHERE trigger_type='COMMAND_ACK_TIMED_OUT' AND source_key=?", String.class, request.requestId().toString()));
        assertEquals(0, timeouts.scan(100));
        assertEquals(1, matches(rule));
    }

    @Test void noFactIsWrittenWithoutAnActiveSubscriptionAndNoHistoryIsInventedWhenOneAppears() {
        UUID device = device();
        ingest(device, 20, now.minusSeconds(10));
        assertEquals(0, facts("ACCEPTED_TELEMETRY", device));
        assertEquals(0, facts("TWIN_STATE_CHANGED", device));
        UUID rule = typedRule(device, "late subscriber", 1, List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY)), List.of(), List.of(alert()));
        assertEquals(0, matches(rule), "a subscription never receives synthetic history");
        ingest(device, 21, now.minusSeconds(5));
        assertEquals(1, facts("ACCEPTED_TELEMETRY", device));
        assertEquals(0, facts("TWIN_STATE_CHANGED", device), "still unsubscribed");
        assertEquals(1, matches(rule));
        lifecycle.transition(rule, "pause", null);
        ingest(device, 22, now.minusSeconds(2));
        assertEquals(1, facts("ACCEPTED_TELEMETRY", device), "paused: nothing needs the fact");
    }
}
