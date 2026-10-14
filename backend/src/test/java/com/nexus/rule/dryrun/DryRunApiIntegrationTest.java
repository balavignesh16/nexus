package com.nexus.rule.dryrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.rule.application.RuleEvaluator;
import com.nexus.rule.domain.*;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * C19 over the real HTTP stack: modes, limits, typed inputs, quality states and the shape of the hypothetical result, on disposable PostgreSQL
 * and Mosquitto. Scenarios 61, 62, 63 and 70 (API half); the zero-effect proof and the live/dry equivalence live in their own classes.
 */
@AutoConfigureMockMvc
class DryRunApiIntegrationTest extends TypedTriggerSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @SpyBean RuleEvaluator evaluator;
    @SpyBean com.nexus.rule.application.RuleExecutionAuthorization authorization;
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    // ------------------------------------------------------------------ helpers

    private record Reply(int status, JsonNode body) {}
    private Reply dry(Object body) throws Exception {
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/rules/dry-run").contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body))).andReturn();
        String text = result.getResponse().getContentAsString();
        return new Reply(result.getResponse().getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    private Map<String, Object> twin(UUID device, String key, String type, Object value, String unit, Instant observed, Instant received) {
        var m = new LinkedHashMap<String, Object>();
        m.put("deviceId", device.toString()); m.put("capabilityKey", key); m.put("valueType", type); m.put("value", value); m.put("unit", unit);
        m.put("observedAt", observed.toString()); if (received != null) m.put("receivedAt", received.toString());
        return m;
    }
    private Map<String, Object> suppliedRequest(UUID device, UUID rule, Instant time, List<Map<String, Object>> twins, String connectivity) {
        var supplied = new LinkedHashMap<String, Object>();
        supplied.put("twins", twins);
        supplied.put("connectivity", connectivity == null ? List.of() : List.of(Map.of("deviceId", device.toString(), "status", connectivity)));
        var req = new LinkedHashMap<String, Object>();
        req.put("mode", "SUPPLIED_STATE");
        req.put("scope", Map.of("deviceId", device.toString()));
        req.put("versions", List.of(Map.of("ruleId", rule.toString())));
        req.put("trigger", Map.of("type", "ACCEPTED_TELEMETRY", "deviceId", device.toString()));
        req.put("simulationTime", time.toString());
        req.put("supplied", supplied);
        return req;
    }
    private UUID tempRule(UUID device, String operator, String threshold, UUID target) {
        UUID id = UUID.randomUUID();
        var temperature = new RuleCondition(RuleField.VALUE, RuleOperator.valueOf(operator), threshold, com.nexus.device.capability.CapabilityType.TEMPERATURE);
        lifecycle.legacySave(new Rule(id, "dry " + operator, true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperature),
            List.of(ledCommand(target, true)), Map.of(), null, null, new RuleRuntime(TELEMETRY, null, null)), null);
        return id;
    }
    private JsonNode condition(JsonNode reply, String capabilityKey) {
        // A MISSING observation carries no capability key, so the capability condition is the (single) VALUE predicate.
        for (JsonNode c : reply.get("versions").get(0).get("conditions")) if ("VALUE".equals(c.path("field").asText())) return c;
        fail("no VALUE evidence for " + capabilityKey + " in " + reply); return null;
    }

    // ------------------------------------------------------------------ SUPPLIED_STATE: a typed, hypothetical decision

    @Test void suppliedStateEvaluatesWithTheProductionEngineAndProposesAHypotheticalCandidate() throws Exception {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, "GT", "10", target);
        var reply = dry(suppliedRequest(device, rule, now, List.of(twin(device, "temperature", "NUMBER", 25, "CELSIUS", now, now)), "ONLINE"));
        assertEquals(200, reply.status(), reply.body().toString());
        var body = reply.body();
        assertEquals(1, body.get("schemaVersion").asInt());
        assertTrue(body.get("hypothetical").asBoolean());
        assertEquals("SUPPLIED", body.get("timeSource").asText());
        assertEquals(now.toString(), body.get("simulatedAt").asText());
        assertTrue(body.get("notice").asText().contains("not an accepted command and not physical actuation"));
        var version = body.get("versions").get(0);
        assertEquals("MATCHED", version.get("outcome").asText());
        assertTrue(version.get("matched").asBoolean());
        assertEquals("GOOD", condition(body, "temperature").get("stateQuality").asText());
        assertEquals(25.0, condition(body, "temperature").get("actualValue").asDouble());
        var candidate = body.get("candidates").get(0);
        assertEquals("SELECTED", candidate.get("disposition").asText());
        assertTrue(candidate.get("wouldBeSelected").asBoolean());
        String id = candidate.get("simulatedId").asText();
        assertTrue(id.startsWith("sim-"));
        assertThrows(IllegalArgumentException.class, () -> UUID.fromString(id), "a simulated id can never be replayed as a command/request id");
        assertEquals(0, count("SELECT count(*) FROM action_history"));
        verify(executor, never()).publishPrepared(any(), any());
    }

    @Test void falseAndZeroAreValuesNotMissingInput() throws Exception {
        UUID device = device(), target = device();
        UUID below = tempRule(device, "LT", "1", target);                    // TEMPERATURE < 1
        var zero = dry(suppliedRequest(device, below, now, List.of(twin(device, "temperature", "NUMBER", 0, "CELSIUS", now, now)), "ONLINE"));
        assertEquals("MATCHED", zero.body().get("versions").get(0).get("outcome").asText(), "0 is a value: 0 < 1");
        assertEquals(0.0, condition(zero.body(), "temperature").get("actualValue").asDouble());
        var missing = dry(suppliedRequest(device, below, now, List.of(), "ONLINE"));
        assertEquals("NOT_MATCHED", missing.body().get("versions").get(0).get("outcome").asText(), "no value at all is MISSING, not 0");
        assertEquals("MISSING", condition(missing.body(), "temperature").get("actualValue").asText());
        // BOOLEAN false on a real boolean capability.
        sql.update("INSERT INTO device_capabilities(id,device_id,capability_type,direction,unit,metadata) VALUES(?,?,'MOTION','READ','BOOLEAN','{}')", UUID.randomUUID(), device);
        UUID motionOff = UUID.randomUUID();
        lifecycle.legacySave(new Rule(motionOff, "motion off", true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()),
            new RuleCondition(RuleField.VALUE, RuleOperator.EQ, "false", com.nexus.device.capability.CapabilityType.MOTION)), List.of(alert()), Map.of(), null, null,
            new RuleRuntime(TELEMETRY, null, null)), null);
        var off = dry(suppliedRequest(device, motionOff, now, List.of(twin(device, "motion", "BOOLEAN", false, "BOOLEAN", now, now)), "ONLINE"));
        assertEquals(200, off.status(), off.body().toString());
        assertEquals("MATCHED", off.body().get("versions").get(0).get("outcome").asText(), "false is a value, not an absent one: " + off.body().get("versions").get(0));
        var on = dry(suppliedRequest(device, motionOff, now, List.of(twin(device, "motion", "BOOLEAN", true, "BOOLEAN", now, now)), "ONLINE"));
        assertEquals("NOT_MATCHED", on.body().get("versions").get(0).get("outcome").asText());
    }

    @Test void aValidEmptyStringIsATypedValueForAPayloadPredicate() throws Exception {
        UUID device = device();
        var definition = Map.of("name", "empty sensor type", "enabled", true, "priority", 1,
            "conditions", List.of(Map.of("field", "EVENT_TYPE", "operator", "EQ", "expectedValue", "TWIN_UPDATED"), Map.of("field", "SENSOR_TYPE", "operator", "EQ", "expectedValue", "")),
            "actions", List.of(Map.of("actionType", "CREATE_ALERT", "parameters", Map.of("severity", "INFO"))));
        java.util.function.Function<Map<String, Object>, Object> request = payload -> {
            var req = new LinkedHashMap<String, Object>();
            req.put("mode", "SUPPLIED_STATE"); req.put("scope", Map.of("deviceId", device.toString()));
            req.put("versions", List.of(Map.of("definition", definition)));
            req.put("trigger", Map.of("type", "LEGACY_DOMAIN_EVENT", "deviceId", device.toString(), "event", Map.of("eventType", "TWIN_UPDATED", "payload", payload)));
            req.put("simulationTime", now.toString()); req.put("supplied", Map.of());
            return req;
        };
        assertEquals("MATCHED", dry(request.apply(Map.of("sensorType", ""))).body().get("versions").get(0).get("outcome").asText(), "\"\" equals the expected \"\"");
        assertEquals("NOT_MATCHED", dry(request.apply(Map.of())).body().get("versions").get(0).get("outcome").asText(), "a missing key is not an empty string");
        assertEquals("NOT_MATCHED", dry(request.apply(Map.of("sensorType", "TEMPERATURE"))).body().get("versions").get(0).get("outcome").asText());
        var inlineVersion = dry(request.apply(Map.of("sensorType", ""))).body().get("versions").get(0);
        assertTrue(inlineVersion.get("ruleId").isNull() && inlineVersion.get("versionId").isNull(), "an inline definition shows no real rule or version id");
        assertEquals("INLINE", inlineVersion.get("source").asText());
    }

    @Test void anExplicitNullIsNotAValueAndAMissingValueKeyIsRejected() throws Exception {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, "GT", "10", target);
        var nulls = new LinkedHashMap<String, Object>(twin(device, "temperature", "NUMBER", null, "CELSIUS", now, now));
        var reply = dry(suppliedRequest(device, rule, now, List.of(nulls), "ONLINE"));
        assertEquals("NOT_MATCHED", reply.body().get("versions").get(0).get("outcome").asText());
        assertEquals("MISSING", condition(reply.body(), "temperature").get("actualValue").asText());
        assertEquals("SUPPLIED_EXPLICIT_NULL", reply.body().get("diagnostics").get(0).get("code").asText());
        var noValue = new LinkedHashMap<String, Object>(twin(device, "temperature", "NUMBER", 1, "CELSIUS", now, now));
        noValue.remove("value");
        var rejected = dry(suppliedRequest(device, rule, now, List.of(noValue), "ONLINE"));
        assertEquals(400, rejected.status());
        assertEquals("VALUE_REQUIRED", rejected.body().get("code").asText());
        var wrongType = dry(suppliedRequest(device, rule, now, List.of(twin(device, "temperature", "NUMBER", "25", "CELSIUS", now, now)), "ONLINE"));
        assertEquals(400, wrongType.status());
        assertEquals("INVALID_SUPPLIED_VALUE", wrongType.body().get("code").asText(), "a numeric string is not a number: no coercion");
    }

    @Test void unavailableStaleAndOfflineStateAreQualityDecisionsNotInventedValues() throws Exception {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, "GT", "10", target);
        var unavailable = dry(suppliedRequest(device, rule, now, List.of(twin(device, "temperature", "NUMBER", 25, "CELSIUS", now, null)), "ONLINE"));
        assertEquals("NOT_MATCHED", unavailable.body().get("versions").get(0).get("outcome").asText());
        assertEquals("UNKNOWN", condition(unavailable.body(), "temperature").get("stateQuality").asText(), "no receipt time is never \"now\"");
        var unobserved = dry(suppliedRequest(device, rule, now, List.of(twin(device, "temperature", "NUMBER", 25, "CELSIUS", now, now)), null));
        assertEquals("UNKNOWN", condition(unobserved.body(), "temperature").get("stateQuality").asText(), "connectivity never observed is UNKNOWN");
        var offline = dry(suppliedRequest(device, rule, now, List.of(twin(device, "temperature", "NUMBER", 25, "CELSIUS", now, now)), "OFFLINE"));
        assertEquals("OFFLINE", condition(offline.body(), "temperature").get("stateQuality").asText());
        var stale = twin(device, "temperature", "NUMBER", 25, "CELSIUS", now.minusSeconds(3600), now.minusSeconds(3600));
        stale.put("freshnessThresholdSeconds", 60);
        var staleReply = dry(suppliedRequest(device, rule, now, List.of(stale), "ONLINE"));
        assertEquals("STALE", condition(staleReply.body(), "temperature").get("stateQuality").asText());
        assertEquals("NOT_MATCHED", staleReply.body().get("versions").get(0).get("outcome").asText(), "GOOD quality is required, a stale value cannot match");
        var wrongUnit = dry(suppliedRequest(device, rule, now, List.of(twin(device, "temperature", "NUMBER", 25, "FAHRENHEIT", now, now)), "ONLINE"));
        assertFalse(wrongUnit.body().get("versions").get(0).get("matched").asBoolean(), "an incompatible unit is never compared");
    }

    // ------------------------------------------------------------------ CURRENT_STATE: backend authority

    @Test void currentStateUsesTheBackendSnapshotAndClockNeverBrowserSuppliedState() throws Exception {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, "GT", "10", target);
        ingest(device, 25, now);
        var req = new LinkedHashMap<String, Object>();
        req.put("mode", "CURRENT_STATE"); req.put("scope", Map.of("deviceId", device.toString()));
        req.put("versions", List.of(Map.of("ruleId", rule.toString())));
        req.put("trigger", Map.of("type", "ACCEPTED_TELEMETRY", "deviceId", device.toString()));
        var reply = dry(req);
        assertEquals(200, reply.status(), reply.body().toString());
        assertEquals("BACKEND_CLOCK", reply.body().get("timeSource").asText());
        assertEquals(now.toString(), reply.body().get("simulatedAt").asText());
        assertEquals("MATCHED", reply.body().get("versions").get(0).get("outcome").asText());
        assertEquals(25.0, condition(reply.body(), "temperature").get("actualValue").asDouble(), "the value came from the persisted Twin");
        // Browser time and browser-supplied state are refused, never mixed into the authoritative view.
        var withTime = new LinkedHashMap<>(req); withTime.put("simulationTime", now.toString());
        assertEquals("SIMULATION_TIME_NOT_ALLOWED", dry(withTime).body().get("code").asText());
        var withState = new LinkedHashMap<>(req);
        withState.put("supplied", Map.of("twins", List.of(twin(device, "temperature", "NUMBER", 99, "CELSIUS", now, now))));
        var refused = dry(withState);
        assertEquals(400, refused.status());
        assertEquals("CURRENT_STATE_REJECTS_SUPPLIED_INPUT", refused.body().get("code").asText());
    }

    @Test void currentStateReadsAnOldBackendObservationAsStaleNotAsTheSuppliedClock() throws Exception {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, "GT", "10", target);
        ingest(device, 25, now);
        now = now.plusSeconds(3 * 3600);        // the backend Clock moves on; nothing new was received
        var req = Map.of("mode", "CURRENT_STATE", "scope", Map.of("deviceId", device.toString()), "versions", List.of(Map.of("ruleId", rule.toString())),
            "trigger", Map.of("type", "ACCEPTED_TELEMETRY", "deviceId", device.toString()));
        var reply = dry(req);
        assertEquals("STALE", condition(reply.body(), "temperature").get("stateQuality").asText());
        assertEquals("NOT_MATCHED", reply.body().get("versions").get(0).get("outcome").asText());
    }

    // ------------------------------------------------------------------ unsupported mode, limits and validation (before any evaluation)

    @Test void historicalReplayIsRejectedWithATypedErrorAndNothingIsEvaluated() throws Exception {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, "GT", "10", target);
        var req = Map.of("mode", "HISTORICAL_REPLAY", "scope", Map.of("deviceId", device.toString()), "versions", List.of(Map.of("ruleId", rule.toString())),
            "trigger", Map.of("type", "ACCEPTED_TELEMETRY", "deviceId", device.toString()), "simulationTime", now.minusSeconds(86400).toString());
        var reply = dry(req);
        assertEquals(422, reply.status());
        assertEquals("HISTORICAL_REPLAY_UNSUPPORTED", reply.body().get("code").asText());
        assertTrue(reply.body().get("message").asText().contains("No historical state was fabricated"));
        assertTrue(reply.body().get("message").asText().contains("open roadmap"));
        assertFalse(reply.body().has("versions"), "no decision was produced");
        verify(evaluator, never()).evaluate(any(), any(), any(com.nexus.rule.snapshot.RuleEvaluationContext.class));
        assertEquals("UNKNOWN_MODE", dry(Map.of("mode", "TIME_TRAVEL", "versions", List.of(Map.of("ruleId", rule.toString())), "trigger", Map.of("type", "MANUAL"))).body().get("code").asText());
        assertEquals("MODE_REQUIRED", dry(Map.of("versions", List.of(Map.of("ruleId", rule.toString())), "trigger", Map.of("type", "MANUAL"))).body().get("code").asText());
    }

    @Test void versionCountConditionCountScopeAndSuppliedLimitsAreEnforcedBeforeEvaluation() throws Exception {
        UUID device = device(), other = device(), target = device();
        UUID rule = tempRule(device, "GT", "10", target);
        var many = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 101; i++) many.add(Map.of("ruleId", rule.toString()));
        var tooMany = dry(Map.of("mode", "CURRENT_STATE", "scope", Map.of("deviceId", device.toString()), "versions", many, "trigger", Map.of("type", "ACCEPTED_TELEMETRY")));
        assertEquals(413, tooMany.status());
        assertEquals("TOO_MANY_VERSIONS", tooMany.body().get("code").asText());

        var conditions = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 101; i++) conditions.add(Map.of("field", "SEVERITY", "operator", "EQ", "expectedValue", "INFO"));
        var wide = Map.of("name", "wide", "enabled", true, "priority", 1, "conditions", conditions, "actions", List.of(Map.of("actionType", "CREATE_ALERT", "parameters", Map.of())));
        var tooWide = dry(Map.of("mode", "SUPPLIED_STATE", "versions", List.of(Map.of("definition", wide)), "trigger", Map.of("type", "LEGACY_DOMAIN_EVENT", "event", Map.of("eventType", "TWIN_UPDATED")),
            "simulationTime", now.toString(), "supplied", Map.of()));
        assertEquals(413, tooWide.status());
        assertEquals("TOO_MANY_CONDITIONS", tooWide.body().get("code").asText());

        var twoScopes = dry(Map.of("mode", "CURRENT_STATE", "scope", Map.of("deviceId", other.toString()), "versions", List.of(Map.of("ruleId", rule.toString())),
            "trigger", Map.of("type", "ACCEPTED_TELEMETRY")));
        assertEquals(400, twoScopes.status());
        assertEquals("MULTIPLE_SCOPES", twoScopes.body().get("code").asText(), "the rule is scoped to another device than the request");
        assertEquals("MULTIPLE_SCOPES", dry(suppliedRequest(device, rule, now, List.of(twin(other, "temperature", "NUMBER", 25, "CELSIUS", now, now)), "ONLINE")).body().get("code").asText());

        verify(evaluator, never()).evaluate(any(), any(), any(com.nexus.rule.snapshot.RuleEvaluationContext.class));
        // Only now the inclusive bound: exactly 100 versions is evaluated.
        var allowed = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i++) allowed.add(Map.of("ruleId", rule.toString()));
        assertEquals(200, dry(Map.of("mode", "CURRENT_STATE", "scope", Map.of("deviceId", device.toString()), "versions", allowed,
            "trigger", Map.of("type", "ACCEPTED_TELEMETRY", "deviceId", device.toString()))).status(), "exactly 100 versions is the inclusive bound");
    }

    @Test void anOversizedBodyIsRefusedBeforeBindingAndBeforeAnyRuleIsLoaded() throws Exception {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, "GT", "10", target);
        var req = suppliedRequest(device, rule, now, List.of(), "ONLINE");
        req.put("versions", List.of(Map.of("ruleId", rule.toString(), "label", "x".repeat(DryRunService.MAX_INPUT_BYTES))));
        var reply = dry(req);
        assertEquals(413, reply.status());
        assertEquals("INPUT_TOO_LARGE", reply.body().get("code").asText());
        verify(evaluator, never()).evaluate(any(), any(), any(com.nexus.rule.snapshot.RuleEvaluationContext.class));
        // Just under the limit is processed normally.
        var ok = suppliedRequest(device, rule, now, List.of(), "ONLINE");
        ok.put("versions", List.of(Map.of("ruleId", rule.toString(), "label", "x".repeat(DryRunService.MAX_INPUT_BYTES - 4096))));
        assertEquals(200, dry(ok).status());
    }

    @Test void aVersionThatIsNotSubscribedToTheTriggerIsReportedNotEvaluated() throws Exception {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, "GT", "10", target);       // subscribes to ACCEPTED_TELEMETRY only
        var req = suppliedRequest(device, rule, now, List.of(twin(device, "temperature", "NUMBER", 25, "CELSIUS", now, now)), "ONLINE");
        req.put("trigger", Map.of("type", "CONNECTIVITY_CHANGED", "deviceId", device.toString(), "attributes", Map.of("connectivity", "ONLINE")));
        var reply = dry(req);
        assertEquals("NOT_ELIGIBLE", reply.body().get("versions").get(0).get("outcome").asText());
        assertFalse(reply.body().get("versions").get(0).get("triggerEligible").asBoolean());
        assertEquals(0, reply.body().get("candidates").size());
        assertEquals(404, dry(Map.of("mode", "CURRENT_STATE", "versions", List.of(Map.of("ruleId", UUID.randomUUID().toString())), "trigger", Map.of("type", "MANUAL"))).status());
    }

    @Test void productionContractDiagnosticsSuppressAnInlineDefinitionJustLikeProduction() throws Exception {
        UUID device = device();
        var definition = Map.of("name", "approval rule", "enabled", true, "priority", 1, "metadata", Map.of("requiresApproval", "true"),
            "conditions", List.of(Map.of("field", "EVENT_TYPE", "operator", "EQ", "expectedValue", "TWIN_UPDATED")),
            "actions", List.of(Map.of("actionType", "CREATE_ALERT", "parameters", Map.of())));
        var reply = dry(Map.of("mode", "SUPPLIED_STATE", "scope", Map.of("deviceId", device.toString()), "versions", List.of(Map.of("definition", definition)),
            "trigger", Map.of("type", "LEGACY_DOMAIN_EVENT", "deviceId", device.toString(), "event", Map.of("eventType", "TWIN_UPDATED")),
            "simulationTime", now.toString(), "supplied", Map.of()));
        assertEquals(200, reply.status(), reply.body().toString());
        assertEquals("CONTRACT_DIAGNOSTICS", reply.body().get("versions").get(0).get("outcome").asText());
        assertEquals("UNSUPPORTED_APPROVAL", reply.body().get("versions").get(0).get("contractDiagnostics").get(0).get("code").asText());
        assertEquals(0, reply.body().get("candidates").size(), "an unsupported approval configuration proposes nothing");
    }

    // ------------------------------------------------------------------ temporal evidence, unknown input, and the body limit without a Content-Length

    @Test void aTemporalConditionIsDecidedFromSuppliedRuntimeEvidenceAddressedByTheDueTrigger() throws Exception {
        UUID device = device();
        UUID rule = temporalRule(device, "for duration", new TemporalSpec(TemporalOperator.FOR_DURATION, 600L, null, null, null, null), true, RuleOperator.GT, "10", List.of(alert()));
        java.util.function.BiFunction<Long, String, Object> request = (sequence, phase) -> {
            var req = new LinkedHashMap<String, Object>();
            req.put("mode", "SUPPLIED_STATE"); req.put("scope", Map.of("deviceId", device.toString()));
            req.put("versions", List.of(Map.of("ruleId", rule.toString())));
            req.put("trigger", Map.of("type", "TEMPORAL_DUE", "deviceId", device.toString(), "attributes", Map.of("conditionId", "c1", "sequence", 1)));
            req.put("simulationTime", now.plusSeconds(600).toString());
            req.put("supplied", Map.of("twins", List.of(twin(device, "temperature", "NUMBER", 25, "CELSIUS", now, now.plusSeconds(590))),
                "connectivity", List.of(Map.of("deviceId", device.toString(), "status", "ONLINE")),
                "temporal", List.of(Map.of("ruleId", rule.toString(), "conditionId", "c1", "operator", "FOR_DURATION", "phase", phase, "sequence", sequence, "generation", 1,
                    "armedAt", now.toString(), "dueAt", now.plusSeconds(600).toString()))));
            return req;
        };
        var matched = dry(request.apply(1L, "SATISFIED"));
        assertEquals("MATCHED", matched.body().get("versions").get(0).get("outcome").asText(), "the due trigger addresses the satisfied occurrence");
        assertTrue(java.util.stream.StreamSupport.stream(matched.body().get("diagnostics").spliterator(), false).anyMatch(d -> d.get("code").asText().equals("TEMPORAL_PROGRESSION_NOT_SIMULATED")),
            "the exact boundary of temporal support is stated in the response: " + matched.body().get("diagnostics"));
        assertEquals("NOT_MATCHED", dry(request.apply(2L, "SATISFIED")).body().get("versions").get(0).get("outcome").asText(), "a different occurrence is not the one the trigger addresses");
        assertEquals("NOT_MATCHED", dry(request.apply(1L, "ARMED")).body().get("versions").get(0).get("outcome").asText(), "an armed, not yet satisfied window does not match");
        // A telemetry trigger does not reach a rule whose only subscription is TEMPORAL_DUE: arming/advancing temporal state is not simulated.
        var telemetry = new LinkedHashMap<String, Object>((Map<String, Object>) request.apply(1L, "SATISFIED"));
        telemetry.put("trigger", Map.of("type", "ACCEPTED_TELEMETRY", "deviceId", device.toString()));
        assertEquals("NOT_ELIGIBLE", dry(telemetry).body().get("versions").get(0).get("outcome").asText());
    }

    @Test void unknownFieldsAndMalformedInputAreRejectedAsBadRequests() throws Exception {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, "GT", "10", target);
        var withUnknown = suppliedRequest(device, rule, now, List.of(), "ONLINE");
        withUnknown.put("approvalOverride", true);
        assertEquals(400, dry(withUnknown).status(), "an unknown field is never silently ignored");
        assertEquals(400, dry("{\"mode\":\"CURRENT_STATE\",\"versions\":[{\"ruleId\":\"not-a-uuid\"}],\"trigger\":{\"type\":\"MANUAL\"}}").status());
        assertEquals(400, dry("{").status());
        assertEquals("UNKNOWN_TRIGGER", dry(Map.of("mode", "CURRENT_STATE", "versions", List.of(Map.of("ruleId", rule.toString())), "trigger", Map.of("type", "TELEPORT"))).body().get("code").asText());
        assertEquals("EVENT_NOT_ALLOWED", dry(Map.of("mode", "CURRENT_STATE", "versions", List.of(Map.of("ruleId", rule.toString())),
            "trigger", Map.of("type", "ACCEPTED_TELEMETRY", "event", Map.of("eventType", "TWIN_UPDATED")))).body().get("code").asText());
        assertEquals("INVALID_VERSION", dry(Map.of("mode", "CURRENT_STATE", "versions", List.of(Map.of("ruleId", rule.toString(), "definition", Map.of("name", "x", "enabled", true, "priority", 1, "conditions", List.of(Map.of("field", "EVENT_TYPE", "operator", "EQ", "expectedValue", "TWIN_UPDATED")), "actions", List.of(Map.of("actionType", "CREATE_ALERT", "parameters", Map.of()))))),
            "trigger", Map.of("type", "ACCEPTED_TELEMETRY"))).body().get("code").asText(), "a version is stored OR inline, never both");
    }

    @Test void theBoundedReadHoldsWithNoDeclaredLengthAndTheFilterOnlyRefusesADeclaredOversizedLengthEarly() throws Exception {
        // Authoritative bound: the handler's bounded read, independent of Content-Length and of the URI spelling.
        byte[] exactly = new byte[DryRunService.MAX_INPUT_BYTES];
        assertEquals(DryRunService.MAX_INPUT_BYTES, RuleDryRunController.readBounded(new java.io.ByteArrayInputStream(exactly), DryRunService.MAX_INPUT_BYTES).length);
        var rejection = assertThrows(DryRunRejection.class, () -> RuleDryRunController.readBounded(new java.io.ByteArrayInputStream(new byte[DryRunService.MAX_INPUT_BYTES + 1]), DryRunService.MAX_INPUT_BYTES));
        assertEquals("INPUT_TOO_LARGE", rejection.code());
        // An endless stream is cut off after limit + one buffer; it is never read to its end.
        var consumed = new java.util.concurrent.atomic.AtomicLong();
        var endless = new java.io.InputStream() {
            @Override public int read() { consumed.incrementAndGet(); return 'a'; }
            @Override public int read(byte[] b, int off, int len) { consumed.addAndGet(len); java.util.Arrays.fill(b, off, off + len, (byte) 'a'); return len; }
        };
        assertThrows(DryRunRejection.class, () -> RuleDryRunController.readBounded(endless, DryRunService.MAX_INPUT_BYTES));
        assertTrue(consumed.get() <= DryRunService.MAX_INPUT_BYTES + 8192L, "read " + consumed.get());

        // Early refusal: a declared oversized length is refused by the filter without the body being read.
        var filter = new DryRunBodyLimitFilter();
        var declared = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/rules/dry-run;x=1") {
            @Override public long getContentLengthLong() { return DryRunService.MAX_INPUT_BYTES + 1L; }
        };
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var chain = new org.springframework.mock.web.MockFilterChain();
        filter.doFilter(declared, response, chain);
        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest(), "the chain was never reached");
        // The same spellings the router accepts are normalized the same way; other endpoints are untouched.
        for (String uri : java.util.List.of("/api/v1/rules/dry-run", "/api/v1/rules/dry-run;x=1", "//api/v1/rules/./dry-run", "/api/v1/;a=b/rules/dry-run")) {
            var req = new org.springframework.mock.web.MockHttpServletRequest("POST", uri);
            assertEquals("/api/v1/rules/dry-run", DryRunBodyLimitFilter.normalized(req), uri);
        }
        // An encoded semicolon is a literal character of the segment, not a path parameter: the router does not map it to this endpoint either.
        assertNotEquals("/api/v1/rules/dry-run", DryRunBodyLimitFilter.normalized(new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/rules/dry-run%3Bx=1")));
        var other = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/rules") {
            @Override public long getContentLengthLong() { return 5L * DryRunService.MAX_INPUT_BYTES; }
        };
        var otherChain = new org.springframework.mock.web.MockFilterChain();
        filter.doFilter(other, new org.springframework.mock.web.MockHttpServletResponse(), otherChain);
        assertNotNull(otherChain.getRequest());
    }

    // ------------------------------------------------------------------ B7-B9: contract boundaries stated, not implied

    private Map<String, Object> inline(Map<String, Object> overrides) {
        var def = new LinkedHashMap<String, Object>(Map.of("name", "inline", "enabled", true, "priority", 1,
            "conditions", List.of(Map.of("field", "EVENT_TYPE", "operator", "EQ", "expectedValue", "TWIN_UPDATED")),
            "actions", List.of(Map.of("actionType", "CREATE_ALERT", "parameters", Map.of()))));
        def.putAll(overrides);
        return def;
    }
    private Object inlineRequest(UUID device, Map<String, Object> definition) {
        return Map.of("mode", "SUPPLIED_STATE", "scope", Map.of("deviceId", device.toString()), "versions", List.of(Map.of("definition", definition)),
            "trigger", Map.of("type", "LEGACY_DOMAIN_EVENT", "deviceId", device.toString(), "event", Map.of("eventType", "TWIN_UPDATED")),
            "simulationTime", now.toString(), "supplied", Map.of());
    }

    @Test void anInlineDefinitionCannotCarryAnUnknownOrUnsupportedFieldThatSilentlyChangesIntent() throws Exception {
        UUID device = device();
        for (var bad : List.of(Map.<String, Object>of("approvalRequired", true), Map.<String, Object>of("safetyOverride", "x"), Map.<String, Object>of("trigger", "MANUAL"),
                Map.<String, Object>of("colour", "red"), Map.<String, Object>of("conditions", List.of(Map.of("field", "EVENT_TYPE", "operator", "EQ", "expectedValue", "TWIN_UPDATED", "bogus", 1))),
                Map.<String, Object>of("actions", List.of(Map.of("actionType", "CREATE_ALERT", "parameters", Map.of(), "payload", "x"))))) {
            var reply = dry(inlineRequest(device, inline(bad)));
            assertEquals(400, reply.status(), bad.toString());
            assertEquals("INVALID_REQUEST", reply.body().get("code").asText(), bad.toString());
        }
        // The supported fields still work, and unsupported policy inside the supported maps is a contract diagnostic, not a silent pass.
        assertEquals(200, dry(inlineRequest(device, inline(Map.of("cooldownSeconds", 30, "repeatPolicy", "EDGE_TRUE")))).status());
        var policy = dry(inlineRequest(device, inline(Map.of("metadata", Map.of("manualApproval", "yes")))));
        assertEquals("CONTRACT_DIAGNOSTICS", policy.body().get("versions").get(0).get("outcome").asText());
    }

    @Test void aDeviceThatIsNotConfiguredIsDiagnosedNotInvented() throws Exception {
        UUID ghost = UUID.randomUUID();
        var reply = dry(inlineRequest(ghost, inline(Map.of())));
        assertEquals(200, reply.status(), reply.body().toString());
        assertEquals("CONTRACT_DIAGNOSTICS", reply.body().get("versions").get(0).get("outcome").asText());
        assertEquals(0, reply.body().get("candidates").size());
        assertTrue(java.util.stream.StreamSupport.stream(reply.body().get("diagnostics").spliterator(), false)
            .anyMatch(d -> d.get("code").asText().equals("DEVICE_NOT_CONFIGURED") && d.get("message").asText().contains("hypothetical devices and hypothetical installed contracts are not supported")), reply.body().get("diagnostics").toString());
    }

    @Test void selectedIsAnArbitrationOutcomeAndTheResponseSaysWhatProductionCouldActuallyExecute() throws Exception {
        UUID device = device(), target = device();
        UUID rule = tempRule(device, "GT", "10", target);
        var request = suppliedRequest(device, rule, now, List.of(twin(device, "temperature", "NUMBER", 25, "CELSIUS", now, now)), "ONLINE");
        var active = dry(request);
        var activeVersion = active.body().get("versions").get(0);
        assertTrue(activeVersion.get("ownership").get("executableInProduction").asBoolean());
        assertEquals("ACTIVE", activeVersion.get("ownership").get("lifecycle").asText());
        assertEquals("NOT_EVALUATED", active.body().get("candidates").get(0).get("acceptanceAuthorization").asText(), "arbitration is not acceptance");
        assertFalse(java.util.stream.StreamSupport.stream(active.body().get("diagnostics").spliterator(), false).anyMatch(d -> d.get("code").asText().equals("NOT_EXECUTABLE_IN_PRODUCTION")));

        lifecycle.transition(rule, "pause", null);
        var paused = dry(request);
        assertEquals("SELECTED", paused.body().get("candidates").get(0).get("disposition").asText(), "arbitration still selects the candidate of a paused rule");
        assertFalse(paused.body().get("versions").get(0).get("ownership").get("executableInProduction").asBoolean());
        assertEquals("PAUSED", paused.body().get("versions").get(0).get("ownership").get("lifecycle").asText());
        assertTrue(java.util.stream.StreamSupport.stream(paused.body().get("diagnostics").spliterator(), false)
            .anyMatch(d -> d.get("code").asText().equals("NOT_EXECUTABLE_IN_PRODUCTION") && d.get("message").asText().contains("production would not execute it")), paused.body().get("diagnostics").toString());

        var unsaved = dry(inlineRequest(device, inline(Map.of())));
        assertTrue(unsaved.body().get("versions").get(0).get("ownership").isNull(), "an unsaved definition has no production owner");
        assertTrue(java.util.stream.StreamSupport.stream(unsaved.body().get("diagnostics").spliterator(), false).anyMatch(d -> d.get("code").asText().equals("UNSAVED_DEFINITION")));
        verifyNoInteractions(authorization);      // the label is a read of stored ownership; production authorization is never invoked
    }
}
