package com.nexus.rule.dryrun;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.application.ActionExecutionEngine;
import com.nexus.rule.application.RuleEvaluator;
import com.nexus.rule.application.RuleLifecycleService;
import com.nexus.rule.domain.*;
import com.nexus.rule.persistence.RuleMatchPersister;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Scenario 60: a dry-run has ZERO production effects, under success AND failure. Proven four ways on disposable PostgreSQL and Mosquitto:
 * a fingerprint of EVERY table before and after each request, spies on every dispatch/persistence/lifecycle/realtime collaborator, a real MQTT
 * subscriber, and a structural test of the dry-run dependency graph. (The tests are mutation-checked by temporarily injecting a forbidden write.)
 */
@AutoConfigureMockMvc
class DryRunZeroEffectIntegrationTest extends TypedTriggerSupport {
    @org.springframework.beans.factory.annotation.Value("${mqtt.broker.url}") String brokerUrl;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DryRunService service;
    @Autowired ConfigurableListableBeanFactory beans;
    @Autowired com.nexus.rule.application.RuleExclusionGroupService exclusion;
    @SpyBean RuleEvaluator evaluator;
    @SpyBean ActionExecutionEngine engine;
    @SpyBean com.nexus.realtime.RealtimePublisher realtime;
    @SpyBean RuleMatchPersister persister;
    @SpyBean RuleLifecycleService lifecycleSpy;
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    // ------------------------------------------------------------------ fingerprints

    /** Row count and content digest of every table: nothing, anywhere, may change. */
    private Map<String, String> fingerprint() {
        var out = new TreeMap<String, String>();
        for (String table : sql.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class)) {
            out.put(table, sql.queryForObject("SELECT count(*)::text || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '') FROM \"" + table + "\" t", String.class));
        }
        return out;
    }
    private void assertUnchanged(Map<String, String> before, String what) {
        var after = fingerprint();
        var changed = new TreeMap<String, String>();
        for (var e : before.entrySet()) if (!Objects.equals(e.getValue(), after.get(e.getKey()))) changed.put(e.getKey(), e.getValue() + " -> " + after.get(e.getKey()));
        assertTrue(changed.isEmpty(), what + " changed production tables: " + changed);
    }
    private void assertNoEffects(String what) {
        verifyNoInteractions(engine, realtime, persister, lifecycleSpy);
        verifyNoPublication();
        verify(planStore, never()).persist(any(), any());
        verify(planStore, never()).recordOutcomes(any());
    }

    // ------------------------------------------------------------------ the production state the dry-runs read

    private record World(UUID device, UUID target, UUID edge, UUID cooled, UUID hysteresis, UUID temporal, UUID excludedA, UUID excludedB, UUID alerting) {}

    private World world() {
        UUID device = device(), target = device(), other = device();
        UUID edge = typedRule(device, "edge", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(target, true)), RepeatPolicy.EDGE_TRUE, null);
        UUID cooled = UUID.randomUUID();
        lifecycle.legacySave(new Rule(cooled, "cooled", true, 2, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperatureAbove("10")),
            List.of(alert()), Map.of(), 600, null, new RuleRuntime(TELEMETRY, null, null)), null);
        UUID hysteresis = UUID.randomUUID();
        lifecycle.legacySave(new Rule(hysteresis, "hysteresis", true, 3, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()),
            new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30", com.nexus.device.capability.CapabilityType.TEMPERATURE, null, null, "h1", null, new HysteresisSpec("25"))),
            List.of(alert()), Map.of(), null, null, new RuleRuntime(TELEMETRY, null, null)), null);
        UUID temporal = temporalRule(device, "for duration", new TemporalSpec(TemporalOperator.FOR_DURATION, 600L, null, null, null, null), true, RuleOperator.GT, "10", List.of(alert()));
        UUID a = typedRule(device, "excluded a", 4, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(other, true)));
        UUID b = typedRule(device, "excluded b", 4, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(other, false)));
        var group = exclusion.create("dry-run zero effect " + UUID.randomUUID());
        exclusion.setMember(group.groupId(), a, 1);
        exclusion.setMember(group.groupId(), b, 2);
        UUID alerting = typedRule(device, "alerting", 5, TELEMETRY, List.of(temperatureAbove("10")), List.of(alert()));
        ingest(device, 35, now);                       // real production state: matches, plans, cooldown, edge latch, hysteresis latch, armed timer
        return new World(device, target, edge, cooled, hysteresis, temporal, a, b, alerting);
    }

    private List<String> battery(World w) throws Exception {
        var requests = new ArrayList<Map<String, Object>>();
        for (String mode : List.of("SUPPLIED_STATE", "CURRENT_STATE")) {
            for (double value : new double[]{50, 5}) {                         // matching and non-matching
                var req = new LinkedHashMap<String, Object>();
                req.put("mode", mode); req.put("scope", Map.of("deviceId", w.device().toString()));
                var versions = new ArrayList<Map<String, Object>>();
                for (UUID rule : List.of(w.edge(), w.cooled(), w.hysteresis(), w.excludedA(), w.excludedB(), w.alerting())) versions.add(Map.of("ruleId", rule.toString()));
                req.put("versions", versions);
                req.put("trigger", Map.of("type", "ACCEPTED_TELEMETRY", "deviceId", w.device().toString()));
                if (mode.equals("SUPPLIED_STATE")) {
                    req.put("simulationTime", now.plusSeconds(7).toString());
                    req.put("supplied", Map.of("twins", List.of(Map.of("deviceId", w.device().toString(), "capabilityKey", "temperature", "valueType", "NUMBER", "value", value,
                            "unit", "CELSIUS", "observedAt", now.toString(), "receivedAt", now.plusSeconds(7).toString())),
                        "connectivity", List.of(Map.of("deviceId", w.device().toString(), "status", "ONLINE")),
                        "cooldowns", List.of(Map.of("ruleId", w.cooled().toString(), "lastMatchedAt", now.toString())),
                        "edgeLatches", List.of(Map.of("ruleId", w.edge().toString(), "latched", true)),
                        "hysteresisLatches", List.of(Map.of("ruleId", w.hysteresis().toString(), "conditionId", "h1", "latched", true))));
                }
                requests.add(req);
            }
        }
        // The temporal rule addressed by a due trigger, with supplied runtime evidence.
        requests.add(Map.of("mode", "SUPPLIED_STATE", "scope", Map.of("deviceId", w.device().toString()), "versions", List.of(Map.of("ruleId", w.temporal().toString())),
            "trigger", Map.of("type", "TEMPORAL_DUE", "deviceId", w.device().toString(), "attributes", Map.of("conditionId", "c1", "sequence", 1)), "simulationTime", now.plusSeconds(900).toString(),
            "supplied", Map.of("temporal", List.of(Map.of("ruleId", w.temporal().toString(), "conditionId", "c1", "operator", "FOR_DURATION", "phase", "SATISFIED", "sequence", 1, "generation", 1,
                "armedAt", now.toString(), "dueAt", now.plusSeconds(600).toString())),
                "twins", List.of(Map.of("deviceId", w.device().toString(), "capabilityKey", "temperature", "valueType", "NUMBER", "value", 40, "unit", "CELSIUS", "observedAt", now.toString(),
                    "receivedAt", now.plusSeconds(890).toString())), "connectivity", List.of(Map.of("deviceId", w.device().toString(), "status", "ONLINE")))));
        // Failure paths: validation, unsupported mode, unknown version, contract diagnostics.
        requests.add(Map.of("mode", "HISTORICAL_REPLAY", "versions", List.of(Map.of("ruleId", w.edge().toString())), "trigger", Map.of("type", "ACCEPTED_TELEMETRY")));
        requests.add(Map.of("mode", "CURRENT_STATE", "versions", List.of(Map.of("ruleId", UUID.randomUUID().toString())), "trigger", Map.of("type", "ACCEPTED_TELEMETRY")));
        requests.add(Map.of("mode", "SUPPLIED_STATE", "versions", List.of(Map.of("ruleId", w.edge().toString())), "trigger", Map.of("type", "ACCEPTED_TELEMETRY")));
        requests.add(Map.of("mode", "CURRENT_STATE", "scope", Map.of("deviceId", UUID.randomUUID().toString()), "versions", List.of(Map.of("ruleId", w.edge().toString())), "trigger", Map.of("type", "ACCEPTED_TELEMETRY")));
        var statuses = new ArrayList<String>();
        for (var request : requests) {
            var result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/rules/dry-run").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))).andReturn();
            statuses.add(result.getResponse().getStatus() + ":" + request.get("mode"));
        }
        // Oversized input.
        var big = new LinkedHashMap<String, Object>(requests.getFirst());
        big.put("versions", List.of(Map.of("ruleId", w.edge().toString(), "label", "x".repeat(DryRunService.MAX_INPUT_BYTES))));
        statuses.add(mvc.perform(MockMvcRequestBuilders.post("/api/v1/rules/dry-run").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(big))).andReturn().getResponse().getStatus() + ":oversize");
        return statuses;
    }

    @Test void everyScenarioLeavesEveryTableUnchangedAndReachesNoDispatchPersistenceLifecycleOrRealtimeCollaborator() throws Exception {
        World w = world();
        assertTrue(count("SELECT count(*) FROM rule_matches") > 0 && count("SELECT count(*) FROM rule_cooldown_state") > 0 && count("SELECT count(*) FROM rule_repeat_state") > 0
            && count("SELECT count(*) FROM rule_hysteresis_state") > 0 && count("SELECT count(*) FROM rule_action_candidates") > 0,
            "the fixture really holds production cooldown, edge, hysteresis, match and plan state to protect");
        resetPublicationSpiesKeepingStubs();
        clearInvocations(engine, realtime, persister, lifecycleSpy, planStore);
        var observer = subscribe();
        try {
            Thread.sleep(500);                                              // retained and in-flight messages from the fixture
            int baseline = received.size();
            var before = fingerprint();
            var statuses = battery(w);
            assertEquals(List.of("200:SUPPLIED_STATE", "200:SUPPLIED_STATE", "200:CURRENT_STATE", "200:CURRENT_STATE", "200:SUPPLIED_STATE", "422:HISTORICAL_REPLAY", "404:CURRENT_STATE",
                "400:SUPPLIED_STATE", "400:CURRENT_STATE", "413:oversize"), statuses, "success and failure scenarios were really exercised");
            assertUnchanged(before, "the dry-run battery");
            assertNoEffects("battery");
            Thread.sleep(500);
            assertEquals(baseline, received.size(), "no MQTT publication of any kind: " + received);
        } finally { observer.disconnect(); observer.close(); }
    }

    @Test void aFailureInTheMiddleOfEvaluationLeavesNothingBehind() throws Exception {
        World w = world();
        resetPublicationSpiesKeepingStubs();
        clearInvocations(engine, realtime, persister, lifecycleSpy, planStore);
        var before = fingerprint();
        doThrow(new IllegalStateException("evaluation blew up")).when(evaluator).evaluate(any(), any(), any(com.nexus.rule.snapshot.RuleEvaluationContext.class));
        try {
            var request = new DryRunRequest("CURRENT_STATE", new DryRunRequest.Scope(w.device()), List.of(new DryRunRequest.VersionSelector(w.edge(), null, null, null)),
                new DryRunRequest.TriggerSpec("ACCEPTED_TELEMETRY", w.device(), null, null, null), null, null);
            assertThrows(IllegalStateException.class, () -> service.run(request));
        } finally { reset(evaluator); }
        assertUnchanged(before, "a failed evaluation");
        assertNoEffects("failure");
    }

    @Test void theDryRunIsOneRepeatableReadReadOnlyTransactionAndTheSnapshotRefusesToRunOutsideIt() throws Exception {
        var tx = DryRunService.class.getMethod("run", DryRunRequest.class).getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertTrue(tx.readOnly());
        assertEquals(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ, tx.isolation());
        assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, tx.propagation());
        // The snapshot entry points open no transaction of their own (they join the single one) and fail closed without it.
        for (String name : List.of("captureForDryRun", "captureSupplied")) {
            var m = Arrays.stream(com.nexus.rule.application.RuleEvaluationSnapshotService.class.getMethods()).filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            assertNull(m.getAnnotation(org.springframework.transaction.annotation.Transactional.class), name + " must not open a second transaction");
        }
        var snapshots = beans.getBean(com.nexus.rule.application.RuleEvaluationSnapshotService.class);
        assertThrows(IllegalStateException.class, () -> snapshots.captureForDryRun(List.of(), null, Instant.now()), "no ambient transaction");
        var transactions = beans.getBean(org.springframework.transaction.PlatformTransactionManager.class);
        var readWrite = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> readWrite.executeWithoutResult(s -> snapshots.captureForDryRun(List.of(), null, Instant.now())), "a read-write transaction is refused");
        var readCommitted = new org.springframework.transaction.support.TransactionTemplate(transactions);
        readCommitted.setReadOnly(true);
        assertThrows(IllegalStateException.class, () -> readCommitted.executeWithoutResult(s -> snapshots.captureForDryRun(List.of(), null, Instant.now())), "READ COMMITTED is refused");
        // A write attempted inside a read-only transaction is refused by PostgreSQL itself.
        var template = new org.springframework.transaction.support.TransactionTemplate(transactions);
        template.setReadOnly(true);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> template.executeWithoutResult(s ->
            sql.update("INSERT INTO rule_cooldown_state(rule_id,scope_key,updated_at) VALUES(gen_random_uuid(),'x',now())")));
    }

    // ------------------------------------------------------------------ structure

    /**
     * The approved collaborators. Each is read-only for the methods the dry-run calls: version/state/membership readers contain only SELECTs
     * (source-scanned below), the evaluator and quality evaluator are pure over a frozen snapshot, and the snapshot service is entered only through
     * two REPEATABLE READ read-only methods. Adding any other dependency fails this test and forces a conscious review.
     */
    private static final Set<Class<?>> APPROVED = Set.of(DryRunEngine.class, com.nexus.rule.persistence.RuleVersionStore.class,
        com.nexus.rule.application.RuleEvaluationSnapshotService.class, RuntimeStateReader.class, com.nexus.twin.application.TwinStateQualityEvaluator.class,
        java.time.Clock.class, RuleEvaluator.class, com.nexus.rule.plan.ExclusionMembershipReader.class, org.springframework.jdbc.core.JdbcTemplate.class);

    private static final List<Class<?>> FORBIDDEN = List.of(
        ActionExecutionEngine.class, com.nexus.action.application.CommandAcceptanceService.class, com.nexus.action.application.executor.CommandExecutor.class,
        RuleMatchPersister.class, com.nexus.rule.plan.RuleActionPlanStore.class, com.nexus.rule.plan.RuleActionPlanner.class, RuleLifecycleService.class,
        com.nexus.realtime.RealtimePublisher.class, com.nexus.rule.application.RuleMatcher.class, MqttClient.class, com.nexus.event.application.EventDispatcher.class,
        com.nexus.rule.persistence.RuleRegistry.class, com.nexus.rule.trigger.RuleTriggerRouter.class, com.nexus.rule.trigger.RuleManualService.class,
        com.nexus.rule.persistence.RuleCooldownStore.class, com.nexus.rule.persistence.RuleRepeatStateStore.class, com.nexus.rule.persistence.RuleHysteresisStore.class,
        com.nexus.rule.temporal.TemporalStateStore.class, com.nexus.rule.temporal.RuleTemporalRuntime.class, org.springframework.context.ApplicationEventPublisher.class);

    private Set<Class<?>> directTypes(Class<?> type) {
        var out = new LinkedHashSet<Class<?>>();
        for (var f : type.getDeclaredFields()) out.add(f.getType());
        for (var c : type.getDeclaredConstructors()) out.addAll(Arrays.asList(c.getParameterTypes()));
        for (var m : type.getDeclaredMethods()) { out.addAll(Arrays.asList(m.getParameterTypes())); out.add(m.getReturnType()); }
        return out;
    }

    @Test void theDryRunObjectsDependOnlyOnApprovedReadOnlyCollaborators() {
        for (Class<?> type : List.of(DryRunService.class, DryRunEngine.class, RuntimeStateReader.class, com.nexus.rule.plan.ExclusionMembershipReader.class)) {
            for (Class<?> dependency : directTypes(type)) {
                boolean ownOrJdk = dependency.isPrimitive() || dependency.isArray() || dependency.getName().startsWith("java.") || dependency.getName().startsWith("com.fasterxml.")
                    || dependency.getName().startsWith("com.nexus.rule.dryrun.") || dependency.getName().startsWith("com.nexus.rule.domain.") || dependency.getName().startsWith("com.nexus.rule.snapshot.")
                    || dependency.getName().startsWith("com.nexus.rule.trigger.TriggerObservation") || dependency.getName().startsWith("com.nexus.rule.plan.")
                    || dependency.getName().startsWith("com.nexus.event.domain.") || dependency.getName().startsWith("com.nexus.twin.domain.")
                    || dependency.getName().startsWith("com.nexus.device.capability.definition.") || dependency.getName().startsWith("com.nexus.rule.temporal.TemporalRuntimeEvidence")
                    || dependency.getName().startsWith("com.nexus.rule.application.RuleDecisionLogic") || dependency.getName().startsWith("org.springframework.http.")
                    || dependency.getName().startsWith("com.nexus.rule.api.dto.") || dependency.getName().startsWith("com.nexus.twin.application.TwinStateQualityEvaluator");
                for (Class<?> forbidden : FORBIDDEN) assertFalse(forbidden.isAssignableFrom(dependency), type.getSimpleName() + " depends on forbidden " + dependency.getName());
                assertTrue(ownOrJdk || APPROVED.contains(dependency) || APPROVED.contains(dependency.getEnclosingClass()), type.getSimpleName() + " has an unreviewed dependency " + dependency.getName());
            }
        }
    }

    @Test void theDryRunSourceContainsNoWriteStatementAndNoPublishingCall() throws Exception {
        var forbiddenTokens = List.of("INSERT ", "UPDATE ", "DELETE ", ".update(", ".batchUpdate(", ".execute(", "FOR UPDATE", "FOR SHARE", "publish", "dispatch", "persist(", ".save(", "saveAll(");
        for (String file : List.of("rule/dryrun/DryRunService.java", "rule/dryrun/DryRunEngine.java", "rule/dryrun/RuntimeStateReader.java", "rule/plan/ExclusionMembershipReader.java",
                "rule/dryrun/RuleDryRunController.java")) {
            String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/nexus/" + file));
            // Strip comments: prose may legitimately say "never dispatches".
            String code = source.replaceAll("(?s)/[*].*?[*]/", "").replaceAll("(?m)//.*$", "");
            for (String token : forbiddenTokens) assertFalse(code.contains(token), file + " contains '" + token + "'");
        }
    }

    // ------------------------------------------------------------------ a real MQTT observer

    private final List<String> received = new CopyOnWriteArrayList<>();
    private MqttClient subscribe() throws Exception {
        var client = new MqttClient(brokerUrl, "dry-run-observer-" + UUID.randomUUID(), new MemoryPersistence());
        client.connect();
        client.subscribe("#", 1, (topic, message) -> received.add(topic + "=" + new String(message.getPayload())));
        return client;
    }
}
