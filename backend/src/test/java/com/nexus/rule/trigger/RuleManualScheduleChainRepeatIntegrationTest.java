package com.nexus.rule.trigger;

import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.rule.application.RuleMutationException;
import com.nexus.rule.domain.*;
import com.nexus.rule.timer.RuleTimerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** MANUAL, SCHEDULE, RULE_MATCHED chains and EDGE_TRUE repeat semantics. */
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class RuleManualScheduleChainRepeatIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleManualService manual;
    @Autowired RuleTimerService timers;
    @Autowired org.springframework.test.web.servlet.MockMvc http;

    private static final List<TriggerSubscription> MANUAL = List.of(new TriggerSubscription(TriggerType.MANUAL));
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    // ------------------------------------------------------------------ MANUAL

    @Test void manualRequestIsAPersistedServerIssuedIdentityAddressedToOneVersion() {
        UUID device = device();
        ingest(device, 25, now.minusSeconds(2));
        UUID rule = typedRule(device, "manual", 1, MANUAL, List.of(temperatureAbove("10")), List.of(alert()));
        UUID bystander = typedRule(device, "manual bystander", 1, MANUAL, List.of(temperatureAbove("10")), List.of(alert()));
        clearInvocations(alerts);
        var issued = manual.request(rule, null);
        assertEquals(1, count("SELECT count(*) FROM rule_trigger_facts WHERE trigger_type='MANUAL' AND source_key=?", issued.requestId().toString()));
        assertEquals(1, matches(rule));
        assertEquals(0, matches(bystander), "a manual request addresses only the selected rule");
        var match = sql.queryForMap("SELECT event_id,trigger_type,trigger_fact_id FROM rule_matches WHERE rule_id=?", rule);
        assertNull(match.get("event_id"));
        assertEquals("MANUAL", match.get("trigger_type"));
        assertEquals(issued.factId(), match.get("trigger_fact_id"));
        verify(alerts, times(1)).execute(any(), any());
    }

    @Test void manualRequestsAreRejectedWithoutSubscriptionOrOwnershipOrMatchingVersion() {
        UUID device = device();
        UUID unsubscribed = typedRule(device, "no manual", 1, TELEMETRY, List.of(), List.of(alert()));
        assertEquals(409, assertThrows(RuleMutationException.class, () -> manual.request(unsubscribed, null)).getStatusCode().value());
        UUID rule = typedRule(device, "manual", 1, MANUAL, List.of(), List.of(alert()));
        assertEquals(409, assertThrows(RuleMutationException.class, () -> manual.request(rule, UUID.randomUUID())).getStatusCode().value());
        lifecycle.transition(rule, "pause", null);
        assertEquals(409, assertThrows(RuleMutationException.class, () -> manual.request(rule, null)).getStatusCode().value());
        assertEquals(404, assertThrows(RuleMutationException.class, () -> manual.request(UUID.randomUUID(), null)).getStatusCode().value());
        assertEquals(0, count("SELECT count(*) FROM rule_trigger_facts WHERE trigger_type='MANUAL' AND device_id=?", device));
    }

    @Test void manualEndpointReturnsAcceptedWithThePersistedIdentity() throws Exception {
        UUID device = device();
        UUID rule = typedRule(device, "manual http", 1, MANUAL, List.of(), List.of(alert()));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/rules/" + rule + "/manual-executions").contentType("application/json").content("{}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.requestId").exists());
        assertEquals(1, matches(rule));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/rules/" + rule + "/runtime"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.matches[0].trigger_type").value("MANUAL"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.candidates[0].disposition").value("SELECTED"));
    }

    // ------------------------------------------------------------------ SCHEDULE

    private UUID scheduled(UUID device, ScheduleSpec spec) {
        return typedRule(device, "scheduled", 1, List.of(new TriggerSubscription(TriggerType.SCHEDULE)), List.of(), List.of(alert()), null, spec);
    }

    @Test void oneShotScheduleFiresOnceAtItsUtcInstantAndNeverAfterConsumption() {
        UUID device = device();
        Instant at = now.plusSeconds(60);
        UUID rule = scheduled(device, new ScheduleSpec(ScheduleSpec.Kind.ONE_SHOT, at, null, null));
        assertEquals(1, pendingTimers(rule));
        now = at.minusMillis(1);
        timers.processDue(10);
        assertEquals(0, matches(rule));
        now = at;
        timers.processDue(10);
        assertEquals(1, matches(rule));
        assertEquals("SCHEDULE", sql.queryForObject("SELECT trigger_type FROM rule_matches WHERE rule_id=?", String.class, rule));
        timers.processDue(10);
        lifecycle.transition(rule, "pause", null);
        lifecycle.transition(rule, "resume", null);
        now = at.plusSeconds(10);
        timers.processDue(10);
        assertEquals(1, matches(rule), "a consumed one-shot is not rearmed by resume");
    }

    @Test void oneShotPausedBeforeDueIsInvalidatedAndRearmedOnResume() {
        UUID device = device();
        Instant at = now.plusSeconds(60);
        UUID rule = scheduled(device, new ScheduleSpec(ScheduleSpec.Kind.ONE_SHOT, at, null, null));
        lifecycle.transition(rule, "pause", null);
        assertEquals(0, pendingTimers(rule));
        now = at.plusSeconds(5);
        timers.processDue(10);
        assertEquals(0, matches(rule), "a paused rule's occurrence never fires");
        lifecycle.transition(rule, "resume", null);
        assertEquals(1, pendingTimers(rule), "never consumed, so resume arms it again for a single late evaluation");
        timers.processDue(10);
        assertEquals(1, matches(rule));
    }

    @Test void fixedIntervalCoalescesMissedSlotsRecordsSkippedCountAndAdvancesIntoTheFuture() {
        UUID device = device();
        Instant start = now;
        UUID rule = scheduled(device, new ScheduleSpec(ScheduleSpec.Kind.FIXED_INTERVAL, null, 60L, start));
        Instant first = start.plusSeconds(60);
        assertEquals(first, sql.queryForObject("SELECT due_at FROM rule_timers WHERE rule_id=? AND status='PENDING'", java.sql.Timestamp.class, rule).toInstant());
        now = first;
        timers.processDue(10);
        assertEquals(1, matches(rule));
        assertEquals(start.plusSeconds(120), sql.queryForObject("SELECT due_at FROM rule_timers WHERE rule_id=? AND status='PENDING'", java.sql.Timestamp.class, rule).toInstant());
        now = start.plusSeconds(400);                              // slots 120, 180, 240, 300, 360 were missed while down
        timers.processDue(10);
        assertEquals(2, matches(rule), "all missed slots coalesce into one evaluation");
        assertEquals(4L, sql.queryForObject("SELECT (attributes->>'skippedSlots')::bigint FROM rule_trigger_facts WHERE trigger_type='SCHEDULE' AND attributes->>'slot'=?", Long.class, start.plusSeconds(120).toString()));
        Instant next = sql.queryForObject("SELECT due_at FROM rule_timers WHERE rule_id=? AND status='PENDING'", java.sql.Timestamp.class, rule).toInstant();
        assertEquals(start.plusSeconds(420), next);
        assertTrue(next.isAfter(now));
        assertEquals(1, pendingTimers(rule), "exactly one pending slot");
    }

    @Test void twoWorkersCannotDecideTheSameScheduledSlotTwice() throws Exception {
        UUID device = device();
        Instant at = now.plusSeconds(10);
        UUID rule = scheduled(device, new ScheduleSpec(ScheduleSpec.Kind.ONE_SHOT, at, null, null));
        now = at;
        var pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) results.add(pool.submit(() -> { start.await(); return timers.processDue(5); }));
        start.countDown();
        for (var r : results) r.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, count("SELECT count(*) FROM rule_trigger_facts WHERE trigger_type='SCHEDULE' AND attributes->>'ruleId'=?", rule.toString()));
        assertEquals(1, matches(rule));
    }

    // ------------------------------------------------------------------ RULE_MATCHED chains

    private UUID chained(UUID device, String name, UUID parent) {
        return typedRule(device, name, 1, List.of(new TriggerSubscription(TriggerType.RULE_MATCHED, Map.of("parentRuleId", parent.toString()))), List.of(), List.of(alert()));
    }

    @Test void aChildRuleRunsOnlyFromAPersistedParentMatchWithRealCausation() throws Exception {
        UUID device = device();
        UUID parent = typedRule(device, "parent", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()));
        UUID child = chained(device, "child", parent);
        UUID stranger = chained(device, "stranger", UUID.randomUUID());
        ingest(device, 25, now.minusSeconds(2));
        router.awaitQueueIdle(15000);
        UUID parentMatch = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, parent);
        assertEquals(1, matches(child));
        assertEquals(0, matches(stranger), "the subscription names its parent rule explicitly");
        var fact = sql.queryForMap("SELECT parent_match_id,chain_depth,root_fact_id,causation_rule_ids::text AS causation FROM rule_trigger_facts WHERE trigger_type='RULE_MATCHED' AND parent_match_id=?", parentMatch);
        assertEquals(parentMatch, fact.get("parent_match_id"));
        assertEquals(1, fact.get("chain_depth"));
        assertNotNull(fact.get("root_fact_id"));
        assertTrue(fact.get("causation").toString().contains(parent.toString()));
        assertEquals(1, count("SELECT count(*) FROM rule_action_candidates WHERE rule_id=? AND disposition='SELECTED'", child));
    }

    @Test void chainDepthIsCappedAtEightAndRejectionIsRecorded() throws Exception {
        UUID device = device();
        UUID previous = typedRule(device, "R0", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()));
        List<UUID> chain = new ArrayList<>(List.of(previous));
        for (int i = 1; i <= 9; i++) { previous = chained(device, "R" + i, previous); chain.add(previous); }
        ingest(device, 25, now.minusSeconds(2));
        for (int i = 0; i < 12; i++) router.awaitQueueIdle(15000);
        for (int i = 0; i <= 8; i++) assertEquals(1, matches(chain.get(i)), "R" + i + " is within depth 8");
        assertEquals(0, matches(chain.get(9)), "R9 would need a depth-9 fact");
        assertEquals(1, count("SELECT count(*) FROM rule_chain_rejections WHERE reason='MAX_DEPTH' AND rule_id=?", chain.get(8)));
    }

    @Test void aLogicalRuleCannotRepeatInItsOwnCausationChain() throws Exception {
        UUID device = device();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        lifecycle.legacySave(new Rule(a, "A", true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperatureAbove("10")), List.of(alert()), Map.of(), null, null,
            new RuleRuntime(List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY), new TriggerSubscription(TriggerType.RULE_MATCHED, Map.of("parentRuleId", b.toString()))), null, null)), null);
        lifecycle.legacySave(new Rule(b, "B", true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString())), List.of(alert()), Map.of(), null, null,
            new RuleRuntime(List.of(new TriggerSubscription(TriggerType.RULE_MATCHED, Map.of("parentRuleId", a.toString()))), null, null)), null);
        ingest(device, 25, now.minusSeconds(2));
        for (int i = 0; i < 4; i++) router.awaitQueueIdle(15000);
        assertEquals(1, matches(a), "A matched from telemetry once; the loop back to A is rejected");
        assertEquals(1, matches(b));
        assertEquals(1, count("SELECT count(*) FROM rule_chain_rejections WHERE reason='CYCLE' AND rule_id=?", a));
    }

    // ------------------------------------------------------------------ EDGE_TRUE

    @Test void edgeTrueFiresOnTheTransitionIntoTrueAndOnlyDefiniteFalseRearmsIt() {
        UUID device = device();
        UUID every = typedRule(device, "every", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()));
        UUID edge = typedRule(device, "edge", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()), RepeatPolicy.EDGE_TRUE, null);
        Instant t0 = now;
        ingest(device, 25, t0);
        now = t0.plusSeconds(5); ingest(device, 26, now);
        assertEquals(2, matches(every));
        assertEquals(1, matches(edge), "the first true fires; still-true does not");
        now = t0.plusSeconds(10);
        connectivity.applyObservedStatus(device, DeviceConnectivityStatus.OFFLINE);
        now = t0.plusSeconds(15); ingest(device, 27, now);        // OFFLINE: ineligible, not a definite false
        now = t0.plusSeconds(20);
        connectivity.applyObservedStatus(device, DeviceConnectivityStatus.ONLINE);
        now = t0.plusSeconds(25); ingest(device, 28, now);
        assertEquals(1, matches(edge), "UNKNOWN/invalid evidence did not manufacture a false edge");
        now = t0.plusSeconds(30); ingest(device, 3, now);          // definite false rearms
        now = t0.plusSeconds(35); ingest(device, 29, now);
        assertEquals(2, matches(edge));
    }

    @Test void pauseResumeStartsAFreshEdgeGenerationWhileCooldownAuthorityIsUntouched() {
        UUID device = device();
        UUID edge = typedRule(device, "edge generation", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()), RepeatPolicy.EDGE_TRUE, null);
        Instant t0 = now;
        ingest(device, 25, t0);
        assertEquals(1, matches(edge));
        lifecycle.transition(edge, "pause", null);
        lifecycle.transition(edge, "resume", null);
        now = t0.plusSeconds(5); ingest(device, 26, now);
        assertEquals(2, matches(edge), "the previous generation's latch no longer applies after resume");
    }

    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;

    @Test void apiRoundTripKeepsTriggersRepeatPolicyAndTemporalConditionWhenAClientEchoesTheResponse() throws Exception {
        UUID device = device();
        var body = Map.of("name", "round trip", "enabled", true, "priority", 1,
            "conditions", List.of(Map.of("field", "DEVICE_ID", "operator", "EQ", "expectedValue", device.toString()),
                Map.of("field", "VALUE", "operator", "GT", "expectedValue", "10", "capabilityType", "TEMPERATURE", "conditionId", "c1",
                    "temporal", Map.of("operator", "FOR_DURATION", "durationSeconds", 60))),
            "actions", List.of(Map.of("actionType", "CREATE_ALERT", "parameters", Map.of())),
            "metadata", Map.of(), "triggers", List.of(Map.of("type", "TEMPORAL_DUE")), "repeatPolicy", "EDGE_TRUE");
        var created = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/rules").contentType("application/json").content(mapper.writeValueAsString(body)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(mapper.readTree(created).get("ruleId").asText());
        var fetched = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/rules/" + id))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        var node = mapper.readTree(fetched);
        assertEquals("TEMPORAL_DUE", node.get("triggers").get(0).get("type").asText());
        assertEquals("EDGE_TRUE", node.get("repeatPolicy").asText());
        assertEquals("c1", node.get("conditions").get(1).get("conditionId").asText());
        int versionsBefore = count("SELECT count(*) FROM rule_versions WHERE rule_id=?", id);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/rules/" + id).contentType("application/json").content(fetched))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals(versionsBefore, count("SELECT count(*) FROM rule_versions WHERE rule_id=?", id), "an unchanged echo creates no new version");
        assertEquals(1, count("SELECT count(*) FROM rules WHERE rule_id=? AND lifecycle='ACTIVE'", id));
        assertEquals("TEMPORAL_DUE", sql.queryForObject("SELECT v.definition->'runtime'->'triggers'->0->>'type' FROM rules r JOIN rule_versions v ON v.version_id=r.active_version_id WHERE r.rule_id=?", String.class, id));
    }
}
