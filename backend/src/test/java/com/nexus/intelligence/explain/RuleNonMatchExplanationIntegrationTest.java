package com.nexus.intelligence.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.rule.ai.ClaimTier;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleOperator;
import com.nexus.rule.domain.RuleRuntime;
import com.nexus.rule.domain.TriggerSubscription;
import com.nexus.rule.domain.TriggerType;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Why a stored rule does NOT match, over real persisted state (ledger P6-020 to P6-022, P6-033, P6-034, P6-036): the rule, the Twin and the
 * connectivity are real rows; the explanation is the deterministic dry-run's per-condition evidence, labelled hypothetical, and nothing is written.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"nexus.ai.rate-limit.requests-per-minute=6000", "nexus.ai.rate-limit.burst=6000"})
class RuleNonMatchExplanationIntegrationTest extends TypedTriggerSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RuleNonMatchExplainer explainer;
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    private UUID tempRule(UUID device, UUID target, Integer cooldownSeconds) {
        UUID id = UUID.randomUUID();
        lifecycle.legacySave(new Rule(id, "p6 temp", true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperatureAbove("10")),
                List.of(ledCommand(target, true)), Map.of(), cooldownSeconds, null, new RuleRuntime(TELEMETRY, null, null)), null);
        return id;
    }

    private UUID tempRule(UUID device, UUID target) {
        return tempRule(device, target, null);
    }

    private static boolean has(Explanation e, ClaimTier tier, String fragment) {
        return e.claims().stream().anyMatch(c -> c.tier() == tier && c.text().contains(fragment));
    }

    private RuleNonMatchExplainer.Result explain(UUID rule, UUID device) {
        return explainer.explain(rule, device, null).orElseThrow();
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : new String[]{"rule_matches", "action_history", "command_outbox", "digital_twins", "telemetry_history", "rules", "rule_versions", "rule_trigger_facts", "rule_timers"})
            counts.put(table, count("SELECT COUNT(*) FROM " + table));
        return counts;
    }

    @Test
    void p6_020_aValueBelowTheThresholdIsExplainedByTheActualValue() {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, target);
        ingest(device, 5, now);
        var r = explain(rule, device);
        var e = r.explanation();
        assertFalse(e.abstained());
        assertTrue(e.hypothetical(), "an evaluation of persisted state, not a historical record");
        assertTrue(has(e, ClaimTier.FACT, "evaluation of the CURRENT persisted state"));
        assertTrue(has(e, ClaimTier.FACT, "outcome is NOT_MATCHED"));
        assertTrue(e.claims().stream().anyMatch(c -> c.tier() == ClaimTier.FACT && c.text().contains("VALUE GT 10") && c.text().contains("actual value is 5")), e.claims().toString());
        assertTrue(has(e, ClaimTier.INFERENCE, "does not match because 1 condition(s) are not satisfied"));
        assertTrue(has(e, ClaimTier.FACT, "Condition 0 (DEVICE_ID EQ " + device + ") is satisfied"));
        assertEquals(List.of(), ExplanationVerifier.verify(r.bundle(), e));
        assertTrue(e.platformStatements().stream().anyMatch(s -> s.contains("NEXUS stores matches, not non-matches")));
    }

    @Test
    void p6_021_missingStaleAndOfflineInputsAreExplainedAsSuchNotAsFalseComparisons() {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, target);
        var missing = explain(rule, device).explanation();
        assertTrue(has(missing, ClaimTier.FACT, "the persisted state holds no value for it"), missing.claims().toString());
        assertFalse(missing.claims().stream().anyMatch(c -> c.text().contains("actual value is")), "an absent input is not a compared value");

        ingest(device, 25, now);
        now = now.plusSeconds(3 * 3600);                                    // the backend clock moves on; nothing new was received
        var stale = explain(rule, device).explanation();
        assertTrue(has(stale, ClaimTier.FACT, "input quality is STALE"), stale.claims().toString());
        assertTrue(stale.claims().stream().anyMatch(c -> c.text().contains("not because of a comparison")));

        now = now.minusSeconds(3 * 3600);
        ingest(device, 25, now);
        connectivity.applyObservedStatus(device, DeviceConnectivityStatus.OFFLINE);
        var offline = explain(rule, device).explanation();
        assertTrue(has(offline, ClaimTier.FACT, "input quality is OFFLINE"), offline.claims().toString());
    }

    @Test
    void p6_022_lifecycleTriggerAndCooldownReasonsAreNamedByTheirOwnFacts() {
        UUID device = device(), target = device();
        UUID paused = tempRule(device, target);
        lifecycle.transition(paused, "pause", null);
        var pausedExplanation = explain(paused, device).explanation();
        assertTrue(has(pausedExplanation, ClaimTier.FACT, "not executable in production: lifecycle PAUSED"), pausedExplanation.claims().toString());

        UUID subscribed = tempRule(device, target);
        var wrongTrigger = explainer.explain(subscribed, device, TriggerType.CONNECTIVITY_CHANGED).orElseThrow().explanation();
        assertTrue(has(wrongTrigger, ClaimTier.FACT, "does not subscribe to the trigger CONNECTIVITY_CHANGED"), wrongTrigger.claims().toString());

        UUID cooling = tempRule(device, target, 600);
        ingest(device, 25, now);                                              // a real match: the cooldown window now holds
        awaitCondition("the match to be recorded", () -> matches(cooling) >= 1);
        var cool = explain(cooling, device).explanation();
        assertTrue(has(cool, ClaimTier.FACT, "inside its cooldown window"), cool.claims().toString());
        assertTrue(cool.summary().contains("does not match now"));
    }

    @Test
    void p6_022_anEdgeLatchAndAHysteresisBandAreNamedByTheirOwnFacts() {
        UUID device = device(), target = device();
        UUID edge = UUID.randomUUID();
        lifecycle.legacySave(new Rule(edge, "p6 edge", true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperatureAbove("10")),
                List.of(alert()), Map.of(), null, null, new RuleRuntime(TELEMETRY, com.nexus.rule.domain.RepeatPolicy.EDGE_TRUE, null)), null);
        ingest(device, 25, now);                                              // the first true evaluation is the edge: it matches and latches
        awaitCondition("the edge match", () -> matches(edge) >= 1);
        var latched = explain(edge, device).explanation();
        assertTrue(has(latched, ClaimTier.FACT, "edge latch from the last edge is still held"), latched.claims().toString());

        UUID banded = UUID.randomUUID();
        lifecycle.legacySave(new Rule(banded, "p6 band", true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()),
                new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30", com.nexus.device.capability.CapabilityType.TEMPERATURE, null, null, "h1", null,
                        new com.nexus.rule.domain.HysteresisSpec("25"))), List.of(alert()), Map.of(), null, null, new RuleRuntime(TELEMETRY, null, null)), null);
        ingest(device, 28, now);                                              // between exit (25) and enter (30), never entered
        var band = explain(banded, device).explanation();
        assertTrue(band.claims().stream().anyMatch(c -> c.tier() == ClaimTier.FACT && c.text().contains("uses hysteresis (enter 30, exit 25)")), band.claims().toString());
        assertTrue(band.claims().stream().anyMatch(c -> c.tier() == ClaimTier.FACT && c.text().contains("the latch was not held")), band.claims().toString());
    }

    @Test
    void p6_020_aRuleThatWouldMatchIsReportedAsSuchNotAsANonMatch() {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, target);
        ingest(device, 25, now);
        var e = explain(rule, device).explanation();
        assertTrue(e.summary().contains("WOULD match now"), e.summary());
        assertTrue(has(e, ClaimTier.FACT, "outcome is MATCHED"));
        assertFalse(e.claims().stream().anyMatch(c -> c.tier() == ClaimTier.INFERENCE && c.text().contains("does not match")));
    }

    @Test
    void p6_033_aCounterfactualIsAnsweredOnlyByTheDeterministicEngineAndLabelledHypothetical() throws Exception {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, target);
        ingest(device, 5, now);
        var before = counts();
        var assumed = new RuleNonMatchExplainer.WhatIf("temperature", "NUMBER", json.getNodeFactory().numberNode(25), "CELSIUS");
        var e = explainer.whatIf(rule, device, null, assumed).orElseThrow().explanation();
        assertTrue(e.hypothetical());
        assertTrue(e.summary().contains("WOULD match now"), e.summary());
        assertTrue(has(e, ClaimTier.FACT, "HYPOTHETICAL evaluation"));
        assertTrue(has(e, ClaimTier.FACT, "Assumed input (supplied by the caller, not persisted): temperature = 25"));
        assertTrue(e.platformStatements().stream().anyMatch(s -> s.contains("What-if assumptions")));
        var low = explainer.whatIf(rule, device, null, new RuleNonMatchExplainer.WhatIf("temperature", "NUMBER", json.getNodeFactory().numberNode(3), "CELSIUS")).orElseThrow().explanation();
        assertTrue(low.summary().contains("does not match now"));
        assertEquals(before, counts(), "a what-if changes nothing");
        // missing assumption fields: the explainer abstains instead of guessing one
        var none = explainer.whatIf(rule, device, null, new RuleNonMatchExplainer.WhatIf("temperature", null, null, null)).orElseThrow().explanation();
        assertTrue(none.abstained());
        // over HTTP
        var body = json.readTree(mvc.perform(get("/api/v1/intelligence/explanations/rules/" + rule + "/what-if").param("deviceId", device.toString()).param("capabilityKey", "temperature")
                .param("valueType", "NUMBER").param("value", "25").param("unit", "CELSIUS")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(body.get("hypothetical").asBoolean());
        mvc.perform(get("/api/v1/intelligence/explanations/rules/" + rule + "/what-if").param("deviceId", device.toString()).param("capabilityKey", "temperature")
                .param("valueType", "NUMBER").param("value", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/intelligence/explanations/rules/" + rule + "/what-if").param("deviceId", device.toString()).param("capabilityKey", "temperature")
                .param("valueType", "OBJECT").param("value", "1")).andExpect(status().isBadRequest());
        assertEquals(before, counts());
    }

    @Test
    void p6_034_theExplainerAbstainsWhenTheNeededEvidenceCannotBeEstablished() {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, target);
        assertTrue(explainer.explain(UUID.randomUUID(), device, null).isEmpty(), "an unknown rule is not found, not explained");
        var noDevice = explainer.explain(rule, null, null).orElseThrow().explanation();
        assertTrue(noDevice.abstained());
        assertTrue(noDevice.abstentionReason().contains("a device is required"));
        assertTrue(noDevice.claims().isEmpty());
        // a legacy rule (no explicit trigger subscription) cannot be evaluated without a named trigger
        UUID legacy = UUID.randomUUID();
        lifecycle.legacySave(new Rule(legacy, "legacy", true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperatureAbove("10")),
                List.of(alert()), Map.of(), null, null, null), null);
        var abstained = explainer.explain(legacy, device, null).orElseThrow().explanation();
        assertTrue(abstained.abstained(), abstained.summary());
        assertTrue(abstained.abstentionReason().contains("no explicit trigger subscription"));
        assertEquals(0, abstained.claims().size(), "an abstention makes no claim");
    }

    @Test
    void p6_036_theEndpointIsReadOnlyGetOnlyAndNothingIsWritten() throws Exception {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, target);
        ingest(device, 5, now);
        var before = counts();
        String url = "/api/v1/intelligence/explanations/rules/" + rule + "/non-match";
        JsonNode body = json.readTree(mvc.perform(get(url).param("deviceId", device.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("RULE_NON_MATCH", body.get("kind").asText());
        assertTrue(body.get("hypothetical").asBoolean());
        mvc.perform(get(url).param("deviceId", device.toString()).param("trigger", "NOT_A_TRIGGER")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/intelligence/explanations/rules/" + UUID.randomUUID() + "/non-match").param("deviceId", device.toString())).andExpect(status().isNotFound());
        mvc.perform(post(url)).andExpect(status().isMethodNotAllowed());
        assertEquals(before, counts());
        verifyNoPublication();
    }
}
