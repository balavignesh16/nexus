package com.nexus.action.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Read-only command lifecycle API against a real database (ledger P4-097, P4-101, P4-103, P4-073, P4-058, P4-114): every assertion is made
 * on what the HTTP response says about durable rows the real dispatcher, the real ACK handler and the real timeout scanner wrote.
 */
@AutoConfigureMockMvc
class CommandLifecycleApiIntegrationTest extends LifecycleFixture {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode lifecycle(UUID id) throws Exception {
        var result = mvc.perform(get("/api/v1/actions/" + id + "/lifecycle")).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private List<String> factNames(JsonNode view) {
        List<String> names = new ArrayList<>();
        view.get("facts").forEach(f -> names.add(f.get("fact").asText()));
        return names;
    }

    private String explanation(JsonNode view) {
        StringBuilder text = new StringBuilder();
        view.get("explanation").forEach(s -> text.append(s.asText()).append('\n'));
        return text.toString();
    }

    // ------------------------------------------------------------------------------------------- P4-097

    @Test
    @Timeout(120)
    void aPublishedCommandShowsItsOrderedFactsDeliveryAndNoAcknowledgementYet() throws Exception {
        UUID id = sendPublished();
        JsonNode view = lifecycle(id);

        assertEquals(id.toString(), view.get("commandId").asText());
        assertEquals(deviceId.toString(), view.get("deviceId").asText());
        assertEquals("PUBLISHED", view.at("/delivery/state").asText());
        assertEquals(List.of("ACCEPTED", "QUEUED", "ATTEMPT_STARTED", "PUBLISHED"), factNames(view));
        assertEquals("NONE", view.at("/ack/status").asText());
        assertFalse(view.at("/ack/timedOut").asBoolean());
        assertTrue(view.at("/latency/acceptedToPublishedMs").asLong() >= 0);
        assertTrue(view.at("/latency/publishedToAckMs").isMissingNode(), "no ACK, so no ACK latency is invented");
        assertFalse("CONVERGED".equals(view.at("/convergence/status").asText()), "publication is not convergence");
        String text = explanation(view);
        assertTrue(text.contains("Published to the MQTT broker. Publication does not prove"), text);
        assertTrue(text.contains("No device acknowledgement has been recorded yet"), text);
        assertTrue(text.contains("A recorded status is not evidence, by itself, that a physical action occurred."), text);
        int previous = 0;
        for (JsonNode fact : view.get("facts")) {
            assertTrue(fact.get("sequence").asInt() > previous, "facts are in strictly increasing sequence");
            previous = fact.get("sequence").asInt();
        }
        assertFalse(view.get("factsTruncated").asBoolean());
    }

    @Test
    @Timeout(120)
    void anAcceptedAckIsAnAcknowledgementOnlyAndItsLatencyComesFromBackendTimestamps() throws Exception {
        UUID id = sendPublished();
        ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null);
        JsonNode view = lifecycle(id);

        assertEquals("ACCEPTED", view.at("/ack/status").asText());
        assertEquals("PUBLISHED", view.at("/delivery/state").asText(), "an ACK does not change the delivery record");
        assertTrue(view.at("/latency/publishedToAckMs").asLong() >= 0);
        assertTrue(view.at("/latency/publishedToConvergenceMs").isMissingNode(), "an ACK is not convergence");
        assertTrue(explanation(view).contains("not proof of physical actuation"), explanation(view));
        assertEquals(List.of("ACCEPTED", "QUEUED", "ATTEMPT_STARTED", "PUBLISHED", "ACK_ACCEPTED"), factNames(view));
    }

    // ------------------------------------------------------------------------------------------- P4-058 device clock skew

    @Test
    @Timeout(180)
    void aDeviceClockFarInThePastOrFutureCannotChangeAnyLatencyFigure() throws Exception {
        long[] claimed = {Instant.now().getEpochSecond() - 86_400, Instant.now().getEpochSecond() + 86_400, 0L};
        List<Long> latencies = new ArrayList<>();
        for (long deviceSeconds : claimed) {
            UUID id = sendPublished();
            ack(id, "ACCEPTED", deviceSeconds, null);
            JsonNode view = lifecycle(id);
            long ms = view.at("/latency/publishedToAckMs").asLong(-1);
            assertTrue(ms >= 0 && ms < 30_000, "ACK latency is a small backend-clock figure whatever the device claims: " + ms);
            latencies.add(ms);
            assertNotNull(view.at("/ack/backendReceivedAt").asText(null));
            assertTrue(view.at("/ack/deviceClaimedTimestamp").asText("").length() > 0 || deviceSeconds == 0L,
                    "the device's claim is shown as a claim, never used as a figure");
        }
        assertEquals(3, latencies.size());
    }

    // ------------------------------------------------------------------------------------------- ACK timeout vs dead letter

    @Test
    @Timeout(180)
    void anAckTimeoutIsNotADeadLetterAndADeadLetterIsNotAnAckTimeout() throws Exception {
        UUID timedOut = sendPublished();
        jdbc.update("UPDATE action_history SET ack_deadline_at = clock_timestamp() - INTERVAL '2 seconds' WHERE request_id = ?", timedOut);
        assertEquals(1, timeoutScanner.scan(50));
        UUID dead = sendDeadLettered(BrokerCommandOutcome.NOT_SENT);

        JsonNode timeoutView = lifecycle(timedOut);
        assertTrue(timeoutView.at("/ack/timedOut").asBoolean(), "the persisted timeout fact is shown");
        assertEquals("PUBLISHED", timeoutView.at("/delivery/state").asText(), "a timeout does not change the delivery record");
        assertTrue(explanation(timeoutView).contains("not a delivery dead letter"), explanation(timeoutView));

        JsonNode deadView = lifecycle(dead);
        assertEquals("DEAD_LETTER", deadView.at("/delivery/state").asText());
        assertEquals("RETRY_EXHAUSTED", deadView.at("/delivery/terminalReason").asText());
        assertFalse(deadView.at("/ack/timedOut").asBoolean());

        Set<String> listed = new HashSet<>();
        json.readTree(mvc.perform(get("/api/v1/actions/dead-letters?limit=100")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("items").forEach(i -> listed.add(i.get("commandId").asText()));
        assertTrue(listed.contains(dead.toString()));
        assertFalse(listed.contains(timedOut.toString()), "an ACK timeout is never listed as a dead letter");
    }

    @Test
    @Timeout(120)
    void anUncertainDeadLetterIsLabelledUncertainAndNeverAsADefiniteFailure() throws Exception {
        UUID id = sendDeadLettered(BrokerCommandOutcome.UNCERTAIN);
        JsonNode view = lifecycle(id);
        assertEquals("UNCERTAIN_EXPIRED", view.at("/delivery/terminalReason").asText());
        String text = explanation(view);
        assertTrue(text.contains("uncertain outcome"), text);
        assertTrue(text.contains("cannot say whether it was delivered"), text);
        assertFalse(text.contains("it was not published"), text);
        JsonNode item = null;
        for (JsonNode candidate : json.readTree(mvc.perform(get("/api/v1/actions/dead-letters?limit=100")).andReturn().getResponse().getContentAsString()).get("items"))
            if (candidate.get("commandId").asText().equals(id.toString())) item = candidate;
        assertNotNull(item);
        assertTrue(item.get("uncertain").asBoolean());
    }

    // ------------------------------------------------------------------------------------------- P4-073 legacy / unknown

    @Test
    @Timeout(120)
    void aCommandAcceptedBeforeTheOutboxExistedIsDeliveryUnknownAndNothingIsInventedForIt() throws Exception {
        UUID legacy = UUID.randomUUID();
        jdbc.update("INSERT INTO action_history(request_id, match_id, device_id, action_type, status, parameters, \"timestamp\", message)"
                + " VALUES (?, ?, ?, 'SEND_COMMAND', 'ACCEPTED_FOR_DISPATCH', '{}'::jsonb, now() - INTERVAL '30 minutes', 'legacy')", legacy, UUID.randomUUID(), deviceId);

        JsonNode before = lifecycle(legacy);
        assertTrue(before.at("/delivery").isMissingNode(), "no outbox row: no delivery section");
        assertTrue(explanation(before).contains("No outbox delivery record exists"), explanation(before));
        assertEquals(1, reconciler.reconcile(50));

        JsonNode view = lifecycle(legacy);
        assertEquals(List.of("DELIVERY_UNKNOWN"), factNames(view));
        assertTrue(view.at("/delivery").isMissingNode());
        assertTrue(explanation(view).contains("Delivery outcome unknown"), explanation(view));
        assertTrue(view.at("/latency/acceptedToPublishedMs").isMissingNode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM command_outbox WHERE command_id = ?", Integer.class, legacy), "never replayed");

        // P4-073: the history endpoint reports the delivery state additively; the existing status field keeps its prior meaning
        JsonNode history = json.readTree(mvc.perform(get("/api/v1/actions/history?deviceId=" + deviceId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode row = null;
        for (JsonNode candidate : history) if (candidate.get("requestId").asText().equals(legacy.toString())) row = candidate;
        assertNotNull(row);
        assertEquals("DELIVERY_UNKNOWN", row.get("deliveryState").asText());
        assertEquals("LEGACY_DELIVERY_UNRECORDED", row.get("deliveryReason").asText());
        assertEquals("ACCEPTED_FOR_DISPATCH", row.get("status").asText(), "the existing status field keeps its prior meaning");
        assertEquals("ACCEPTED_FOR_DISPATCH", row.get("commandStatus").asText());
    }

    // ------------------------------------------------------------------------------------------- not found / invalid

    @Test
    void anUnknownCommandIs404AndAMalformedIdIs400() throws Exception {
        mvc.perform(get("/api/v1/actions/" + UUID.randomUUID() + "/lifecycle")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/actions/not-a-uuid/lifecycle")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------------------- P4-103 bounded pagination

    @Test
    @Timeout(240)
    void deadLettersArePagedNewestFirstWithAStableCursorAndBoundedParameters() throws Exception {
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            created.add(sendDeadLettered(BrokerCommandOutcome.NOT_SENT));
            deviceId = newDevice();                                    // a fresh device each time: no per-device hold interferes
        }
        JsonNode first = json.readTree(mvc.perform(get("/api/v1/actions/dead-letters?limit=2")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(2, first.get("items").size());
        assertNotNull(first.get("nextCursor"));
        String cursor = first.get("nextCursor").asText();
        JsonNode second = json.readTree(mvc.perform(get("/api/v1/actions/dead-letters").param("limit", "2").param("cursor", cursor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> seen = new ArrayList<>();
        first.get("items").forEach(i -> seen.add(i.get("commandId").asText()));
        second.get("items").forEach(i -> seen.add(i.get("commandId").asText()));
        assertEquals(seen.size(), new HashSet<>(seen).size(), "no row appears on two pages");
        for (UUID id : created) assertTrue(seen.contains(id.toString()), "every dead letter is reachable by paging");
        Instant previous = null;
        for (String raw : seen) {
            Instant at = Instant.parse(jdbc.queryForObject("SELECT to_char(terminal_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM command_outbox WHERE command_id = ?::uuid", String.class, raw));
            if (previous != null) assertFalse(at.isAfter(previous), "newest first");
            previous = at;
        }

        for (String bad : List.of("limit=0", "limit=101", "limit=-1", "limit=abc", "cursor=garbage"))
            mvc.perform(get("/api/v1/actions/dead-letters?" + bad)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/actions/dead-letters").param("limit", "5").param("cursor", "2026-01-01T00:00:00Z|not-a-uuid")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------------------- read-only, no exposure

    private static final Set<String> VIEW_KEYS = Set.of("commandId", "deviceId", "matchId", "actionType", "executionStatus", "delivery", "ack", "convergence",
            "latency", "explanation", "facts", "factsTruncated", "state", "attemptCount", "maxAttempts", "terminalReason", "lastErrorCode", "acceptedAt",
            "firstPublishConfirmedAt", "terminalAt", "status", "timedOut", "deadlineAt", "backendReceivedAt", "timedOutAt", "deviceClaimedTimestamp",
            "updatedAt", "acceptedToPublishedMs", "publishedToAckMs", "publishedToConvergenceMs", "acceptedToTerminalMs", "sequence", "fact", "fromState",
            "toState", "attempt", "reason", "source", "occurredAt");

    private void collectKeys(JsonNode node, Set<String> keys) {
        if (node.isObject()) node.fields().forEachRemaining(e -> { keys.add(e.getKey()); collectKeys(e.getValue(), keys); });
        else if (node.isArray()) node.forEach(child -> collectKeys(child, keys));
    }

    @Test
    @Timeout(120)
    void theResponsesExposeOnlyTheAllowListedFieldsAndNeverParametersPayloadsTopicsOrDeviceText() throws Exception {
        UUID id = sendPublished();
        ack(id, "REJECTED", Instant.now().getEpochSecond(), "SECRET-DEVICE-TEXT-should-never-appear");
        String body = mvc.perform(get("/api/v1/actions/" + id + "/lifecycle")).andReturn().getResponse().getContentAsString();
        Set<String> keys = new HashSet<>();
        collectKeys(json.readTree(body), keys);
        Set<String> unexpected = new HashSet<>(keys);
        unexpected.removeAll(VIEW_KEYS);
        assertTrue(unexpected.isEmpty(), "unexpected response fields: " + unexpected);
        for (String forbidden : List.of("parameters", "payload", "payloadText", "payloadSha256", "topic", "leaseOwner", "SECRET", "nexus/devices"))
            assertFalse(body.contains(forbidden), forbidden + " must not be exposed: " + body);
        assertTrue(body.contains("DEVICE_REASON_OTHER"), "a device-chosen reason is shown only as the generic code: " + body);
        String deadLetters = mvc.perform(get("/api/v1/actions/dead-letters")).andReturn().getResponse().getContentAsString();
        for (String forbidden : List.of("parameters", "payload", "topic", "leaseOwner"))
            assertFalse(deadLetters.contains(forbidden), forbidden);
    }

    @Test
    @Timeout(120)
    void everyEndpointIsReadOnlyAndRepeatedReadsChangeNoRow() throws Exception {
        UUID published = sendPublished();
        UUID dead = sendDeadLettered(BrokerCommandOutcome.NOT_SENT);
        Map<String, String> before = fingerprint();
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/v1/actions/" + published + "/lifecycle")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/actions/" + dead + "/lifecycle")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/actions/dead-letters?limit=5")).andExpect(status().isOk());
        }
        assertEquals(before, fingerprint(), "reading the lifecycle changed no durable row");
        for (String path : List.of("/api/v1/actions/" + published + "/lifecycle", "/api/v1/actions/dead-letters")) {
            mvc.perform(post(path)).andExpect(status().isMethodNotAllowed());
            mvc.perform(put(path)).andExpect(status().isMethodNotAllowed());
            mvc.perform(delete(path)).andExpect(status().isMethodNotAllowed());
        }
        assertEquals(before, fingerprint(), "refused mutations changed nothing either");
    }

    private Map<String, String> fingerprint() {
        Map<String, String> out = new TreeMap<>();
        for (String table : List.of("action_history", "command_outbox", "command_state_history", "current_twin_verification_state"))
            out.put(table, jdbc.queryForObject("SELECT count(*)::text || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '') FROM " + table + " t", String.class));
        return out;
    }

    // ------------------------------------------------------------------------------------------- history enrichment (additive)

    @Test
    @Timeout(120)
    void theHistoryEndpointCarriesTheAdditiveDeliveryStateAndKeepsItsExistingFields() throws Exception {
        UUID dead = sendDeadLettered(BrokerCommandOutcome.NOT_SENT);
        JsonNode rows = json.readTree(mvc.perform(get("/api/v1/actions/history?deviceId=" + deviceId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode row = null;
        for (JsonNode candidate : rows) if (candidate.get("requestId").asText().equals(dead.toString())) row = candidate;
        assertNotNull(row);
        assertEquals("DEAD_LETTER", row.get("deliveryState").asText());
        assertEquals("RETRY_EXHAUSTED", row.get("deliveryReason").asText());
        for (String existing : List.of("executionId", "requestId", "matchId", "deviceId", "actionType", "status", "timestamp", "commandStatus"))
            assertTrue(row.has(existing), "existing field still present: " + existing);
    }
}
