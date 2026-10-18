package com.nexus.action.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.intelligence.explain.CommandExplainer;
import com.nexus.intelligence.explain.ExplanationVerifier;
import com.nexus.rule.ai.ClaimTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Command explanations over the REAL persisted lifecycle (ledger P6-025 to P6-031, P6-036, P6-037): the rows are written by the real dispatcher, the
 * real ACK handler and the real timeout scanner, and the explanation is read back through the service and over HTTP. Also proves the read-only guarantee by
 * comparing row counts of every table a command touches before and after.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"nexus.ai.rate-limit.requests-per-minute=6000", "nexus.ai.rate-limit.burst=6000"})
class CommandExplanationIntegrationTest extends LifecycleFixture {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CommandExplainer explainer;

    private JsonNode explanation(UUID id) throws Exception {
        var result = mvc.perform(get("/api/v1/intelligence/explanations/commands/" + id)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static boolean has(JsonNode e, String tier, String fragment) {
        for (JsonNode c : e.get("claims")) if (tier.equals(c.get("tier").asText()) && c.get("text").asText().contains(fragment)) return true;
        return false;
    }

    private static boolean any(JsonNode e, String fragment) {
        for (JsonNode c : e.get("claims")) if (c.get("text").asText().contains(fragment)) return true;
        return false;
    }

    private Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new String[]{"action_history", "command_outbox", "command_state_history", "pipeline_traces", "rule_matches", "digital_twins", "telemetry_history",
                "current_desired_twin_state", "current_twin_verification_state", "devices", "rules", "rule_versions"})
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        return counts;
    }

    @Test
    @Timeout(120)
    void p6_025_p6_026_aPublishedCommandIsExplainedFromItsRecordedFactsAndTheEvidenceResolves() throws Exception {
        UUID id = sendPublished();
        JsonNode e = explanation(id);
        assertEquals("COMMAND_LIFECYCLE", e.get("kind").asText());
        assertFalse(e.get("abstained").asBoolean());
        assertFalse(e.get("hypothetical").asBoolean());
        assertTrue(has(e, "FACT", "was accepted at"));
        assertTrue(has(e, "FACT", "delivery state is PUBLISHED after 1 publication attempt(s)"));
        assertTrue(has(e, "FACT", "Publication was first confirmed at"));
        assertTrue(has(e, "FACT", "No device acknowledgement is recorded yet"));
        assertTrue(has(e, "FACT", "qualified verification status is "), "the command's own verification record is stated");
        assertFalse(has(e, "FACT", "status is CONVERGED"), "publication is not convergence");
        assertTrue(has(e, "INFERENCE", "No stage has a failing fact"));
        // every cited reference resolves to the persisted row it names (the service verifies it, and so does this test)
        var result = explainer.explain(id).orElseThrow();
        assertEquals(java.util.List.of(), ExplanationVerifier.verify(result.bundle(), result.explanation()));
        assertTrue(result.explanation().claims().stream().filter(c -> c.tier() == ClaimTier.FACT).allMatch(c -> result.bundle().resolve(c.evidenceRef()).isPresent()));
        assertEquals("PUBLISHED", result.bundle().resolve("command:" + id + "/delivery/state").orElseThrow().value());
        String statements = e.get("platformStatements").toString();
        assertTrue(statements.contains("BROKER_CONFIRMED") && statements.contains("None of them means the device received or physically executed the command."));
    }

    @Test
    @Timeout(120)
    void p6_029_p6_031_anAcceptedAckIsOnlyAnAcknowledgementAndConvergenceStaysSeparate() throws Exception {
        UUID id = sendPublished();
        ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null);
        JsonNode e = explanation(id);
        assertTrue(has(e, "FACT", "device acknowledgement status is ACCEPTED"));
        assertFalse(any(e, "actuat") || any(e, "executed"), e.toString());
        assertFalse(has(e, "FACT", "status is CONVERGED"), "an ACK is not convergence");
        assertTrue(has(e, "FACT", "qualified verification status is "), "convergence is its own separate statement");
    }

    @Test
    @Timeout(180)
    void p6_028_anAckTimeoutIsExplainedByItsPersistedFactAndNamesTheAckStage() throws Exception {
        UUID id = sendPublished();
        jdbc.update("UPDATE action_history SET ack_deadline_at = clock_timestamp() - INTERVAL '2 seconds' WHERE request_id = ?", id);
        assertEquals(1, timeoutScanner.scan(50));
        JsonNode e = explanation(id);
        assertTrue(has(e, "FACT", "An ACK timeout was recorded at"));
        assertTrue(has(e, "INFERENCE", "The first stage with a failing or uncertain fact is ACK"));
        assertFalse(any(e, "No device acknowledgement is recorded yet"));
        assertTrue(e.get("platformStatements").toString().contains("not a delivery dead letter"), "the Phase 4 platform sentence is carried unchanged");
    }

    @Test
    @Timeout(180)
    void p6_026_aDeadLetteredCommandIsExplainedAsNotPublishedWithItsRecordedTerminalReason() throws Exception {
        UUID id = sendDeadLettered(BrokerCommandOutcome.NOT_SENT);
        JsonNode e = explanation(id);
        assertTrue(has(e, "FACT", "delivery state is DEAD_LETTER"));
        assertTrue(has(e, "FACT", "recorded terminal reason is RETRY_EXHAUSTED"));
        assertTrue(has(e, "INFERENCE", "The first stage with a failing or uncertain fact is DELIVERY"));
        assertFalse(any(e, "has not been confirmed as published") && any(e, "Publication was first confirmed"));
    }

    @Test
    @Timeout(180)
    void p6_027_anUncertainAttemptIsExplainedAsUncertain() throws Exception {
        UUID id = sendDeadLettered(BrokerCommandOutcome.UNCERTAIN);
        JsonNode e = explanation(id);
        assertTrue(any(e, "ended with an uncertain outcome (PUBLISH_TIMEOUT); the record cannot say whether the broker received it."), e.toString());
        assertTrue(has(e, "FACT", "recorded terminal reason is UNCERTAIN_EXPIRED"));
        assertFalse(any(e, "was delivered") || any(e, "succeeded"));
        assertTrue(e.get("platformStatements").toString().contains("may already have reached the broker"));
    }

    @Test
    @Timeout(180)
    void p6_036_p6_037_theEndpointIsReadOnlyBoundedAndExposesNoParametersPayloadsOrTopics() throws Exception {
        UUID published = sendPublished();
        UUID dead = sendDeadLettered(BrokerCommandOutcome.NOT_SENT);
        var before = counts();
        for (UUID id : new UUID[]{published, dead, published}) {
            String body = mvc.perform(get("/api/v1/intelligence/explanations/commands/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String lower = body.toLowerCase();
            for (String forbidden : new String[]{"payload", "parameters", "nexus/devices", "\"topic\""}) assertFalse(lower.contains(forbidden), forbidden);
            assertTrue(body.length() < 20_000, "bounded: " + body.length());
            explainer.explain(id).orElseThrow();
        }
        assertEquals(before, counts(), "explaining changes no row of any table a command touches");
        UUID unknown = UUID.randomUUID();
        mvc.perform(get("/api/v1/intelligence/explanations/commands/" + unknown)).andExpect(status().isNotFound());
        String path = "/api/v1/intelligence/explanations/commands/" + published;
        mvc.perform(post(path)).andExpect(status().isMethodNotAllowed());
        mvc.perform(put(path)).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(path)).andExpect(status().isMethodNotAllowed());
        assertEquals(before, counts());
    }
}
