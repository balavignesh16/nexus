package com.nexus.intelligence.anomaly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.intelligence.model.ModelProvider;
import com.nexus.intelligence.model.ModelProviderException;
import com.nexus.intelligence.model.ProviderIdentity;
import com.nexus.intelligence.recommend.Recommendation;
import com.nexus.intelligence.recommend.RecommendationNarrator;
import com.nexus.intelligence.recommend.RecommendationService;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleOperator;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Layer 3 on a real PostgreSQL with the real telemetry pipeline, rule lifecycle and arbitration: findings and recommendations are computed from what is
 * actually persisted, cite rows that exist, are repeatable, change nothing, and need no model. Ledger P6-057..P6-071.
 */
@AutoConfigureMockMvc
class AnomalyEvidenceIntegrationTest extends TypedTriggerSupport {
    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry r) {
        r.add("nexus.ai.send-device-evidence", () -> "true");
        r.add("nexus.ai.rate-limit.requests-per-minute", () -> "6000");
        r.add("nexus.ai.rate-limit.burst", () -> "6000");
    }

    @MockBean ModelProvider provider;
    @Autowired AnomalyService anomalies;
    @Autowired RecommendationService recommendations;
    @Autowired RecommendationNarrator narrator;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;

    private final AtomicReference<String> reply = new AtomicReference<>();

    @BeforeEach
    void provider() throws Exception {
        when(provider.available()).thenReturn(true);
        when(provider.identity()).thenReturn(new ProviderIdentity("fake", "fake-1", "cfg"));
        when(provider.complete(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(i -> {
            if (reply.get() == null) throw new ModelProviderException("HTTP_500", false);
            return reply.get();
        });
    }

    // ------------------------------------------------------------ helpers

    private void insert(UUID device, String key, String type, Instant at, Double value, String unit) {
        sql.update("INSERT INTO telemetry_history(id, device_id, timestamp, sensor_type, value, unit, capability_key, value_type, numeric_value, boolean_value) VALUES (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), device, java.sql.Timestamp.from(at), key.toUpperCase(), type.equals("BOOLEAN") ? null : value, unit == null ? "" : unit, key, type,
                type.equals("NUMBER") ? value : null, type.equals("BOOLEAN") ? Boolean.TRUE : null);
    }

    /** One reading a minute for 24 hours ending one minute before {@code now}: noise around 21 with the given tweak applied to minute index i. */
    private void minuteSeries(UUID device, String key, long seed, java.util.function.IntToDoubleFunction tweak) {
        Random r = new Random(seed);
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < 24 * 60; i++) {
            double v = Math.round((21.0 + 0.3 * r.nextGaussian()) * 10) / 10.0;
            v = tweak.applyAsDouble(i) == Double.MIN_VALUE ? v : tweak.applyAsDouble(i);
            batch.add(new Object[] {UUID.randomUUID(), device, java.sql.Timestamp.from(now.minus(Duration.ofMinutes(24 * 60 - i))), key.toUpperCase(), v, "CELSIUS", key, "NUMBER", v});
        }
        sql.batchUpdate("INSERT INTO telemetry_history(id, device_id, timestamp, sensor_type, value, unit, capability_key, value_type, numeric_value) VALUES (?,?,?,?,?,?,?,?,?)", batch);
    }

    private static final int RECENT_START = 24 * 60 - 120;

    private Finding finding(AnomalyReport r, String cap, Finding.Category c) {
        return r.findings().stream().filter(f -> f.capabilityKey().equals(cap) && f.category() == c).findFirst().orElseThrow();
    }

    private Map<String, Long> footprint() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String t : List.of("rules", "rule_versions", "rule_matches", "rule_action_candidates", "command_outbox", "command_state_history", "action_history", "digital_twins", "twin_state_transitions",
                "telemetry_history", "domain_events", "device_connectivity", "devices", "device_capabilities", "ai_rule_drafts", "ai_rule_draft_decisions", "rule_trigger_facts", "rule_timers"))
            m.put(t, sql.queryForObject("SELECT count(*) FROM " + t, Long.class));
        return m;
    }

    // ------------------------------------------------------------ detection on real persisted rows

    @Test
    void p6_058_p6_059_p6_060_p6_066_findingsOnPersistedRowsCiteRowsThatExist() {
        UUID device = device();
        minuteSeries(device, "temperature", 21, i -> i >= RECENT_START ? 21.3 : Double.MIN_VALUE);                 // stuck
        minuteSeries(device, "humidity", 22, i -> i == RECENT_START + 30 ? 80.0 : Double.MIN_VALUE);               // outlier + spike
        AnomalyReport r = anomalies.analyse(device, Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(r.deviceFound()).isTrue();
        assertThat(r.capabilitiesAnalysed()).containsExactly("humidity", "temperature");
        assertThat(finding(r, "temperature", Finding.Category.FLATLINE).status()).isEqualTo(Finding.Status.OBSERVED);
        assertThat(finding(r, "humidity", Finding.Category.OUTLIER).status()).isEqualTo(Finding.Status.OBSERVED);
        assertThat(finding(r, "humidity", Finding.Category.SPIKE).status()).isEqualTo(Finding.Status.OBSERVED);
        assertThat(finding(r, "humidity", Finding.Category.FLATLINE).status()).isEqualTo(Finding.Status.NOT_OBSERVED);
        // every cited row exists and holds the cited value; every window reference names the device and capability
        for (Finding f : r.findings()) {
            assertThat(f.evidenceRefs().getFirst()).startsWith("telemetry:" + device + "/" + f.capabilityKey());
            assertThat(f.evaluatedAt()).isEqualTo(now);
            assertThat(f.confirmedFault()).isFalse();
            for (Map<String, Object> m : f.measurements()) {
                if (m.get("rowId") == null) continue;
                assertThat(count("SELECT count(*) FROM telemetry_history WHERE id = ?::uuid AND device_id = ? AND capability_key = ? AND numeric_value = ?", m.get("rowId"), device, f.capabilityKey(), m.get("value")))
                        .as("row %s of %s %s", m.get("rowId"), f.capabilityKey(), f.category()).isEqualTo(1);
            }
        }
        assertThat(r.connectivity()).isNotNull();
        assertThat(r.connectivity().status()).isEqualTo("ONLINE"); // a separate fact, beside the findings
    }

    @Test
    void p6_057_theRealTelemetryPipelineIsReadAndAShortSeriesIsInsufficientEvidence() {
        UUID device = device();
        Random r = new Random(5);
        for (int i = 0; i < 40; i++) ingest(device, 21 + r.nextGaussian() * 0.3, now.minus(Duration.ofMinutes(260 - i * 6)));
        AnomalyReport report = anomalies.analyse(device, Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(report.capabilitiesAnalysed()).containsExactly("temperature");
        assertThat(report.findings()).isNotEmpty();
        assertThat(report.findings()).allMatch(f -> f.samples().get("kept").equals(40));
        // 24 baseline readings are far below the 100 the measured false-flag table requires: the answer is insufficient evidence, not a guess
        for (Finding.Category c : List.of(Finding.Category.OUTLIER, Finding.Category.SPIKE, Finding.Category.FLATLINE)) {
            assertThat(finding(report, "temperature", c).status()).as(c.name()).isEqualTo(Finding.Status.INSUFFICIENT_EVIDENCE);
            assertThat(finding(report, "temperature", c).reason()).isEqualTo("BASELINE_TOO_SMALL");
        }
    }

    @Test
    void p6_058_aBooleanCapabilityIsReportedNotApplicableOverHttp() throws Exception {
        UUID device = device();
        for (int i = 0; i < 300; i++) insert(device, "relay", "BOOLEAN", now.minus(Duration.ofMinutes(300 - i)), null, null);
        String body = mvc.perform(get("/api/v1/intelligence/anomalies/devices/" + device).param("capabilityKey", "relay")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode findings = mapper.readTree(body).get("findings");
        assertThat(findings).hasSize(4);
        findings.forEach(f -> {
            assertThat(f.get("status").asText()).isEqualTo("NOT_APPLICABLE");
            assertThat(f.get("reason").asText()).isEqualTo("NOT_A_CONTINUOUS_NUMERIC_CAPABILITY");
        });
    }

    @Test
    void p6_057_p6_071_repeatedEvaluationOfUnchangedEvidenceIsIdenticalAndCostIsBounded() {
        UUID device = device();
        minuteSeries(device, "temperature", 31, i -> i >= RECENT_START ? 21.3 : Double.MIN_VALUE);
        AnomalyReport a = anomalies.analyse(device, Optional.empty(), Optional.empty(), Optional.empty());
        AnomalyReport b = anomalies.analyse(device, Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(b).isEqualTo(a);
        RecommendationService.Report ra = recommendations.recommend(Optional.of(device), Optional.empty(), Optional.empty(), Optional.empty());
        RecommendationService.Report rb = recommendations.recommend(Optional.of(device), Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(rb.recommendations()).isEqualTo(ra.recommendations());
        assertThat(ra.recommendations().stream().map(Recommendation::id)).doesNotHaveDuplicates();
        // the read limit bounds the work: more rows than max-samples are not all read, and the result says so
        UUID busy = device();
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < 5_100; i++) batch.add(new Object[] {UUID.randomUUID(), busy, java.sql.Timestamp.from(now.minusSeconds(10L + 15L * (5_100 - i))), "TEMPERATURE", 21.0 + (i % 7) * 0.1, "CELSIUS", "temperature", "NUMBER", 21.0 + (i % 7) * 0.1});
        sql.batchUpdate("INSERT INTO telemetry_history(id, device_id, timestamp, sensor_type, value, unit, capability_key, value_type, numeric_value) VALUES (?,?,?,?,?,?,?,?,?)", batch);
        AnomalyReport big = anomalies.analyse(busy, Optional.empty(), Optional.of(30), Optional.empty());
        assertThat(big.findings().getFirst().quality().get("readLimitReached")).isEqualTo(true);
        assertThat(big.findings().getFirst().quality().get("rawRows")).isEqualTo(5_000);
        // more capabilities than the per-device limit are cut, and it says so
        UUID wide = device();
        for (int i = 0; i < 22; i++) insert(wide, "cap_" + (char) ('a' + i), "NUMBER", now.minusSeconds(60), 1.0, "X");
        AnomalyReport many = anomalies.analyse(wide, Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(many.capabilityListTruncated()).isTrue();
        assertThat(many.capabilitiesAnalysed()).hasSize(20);
    }

    @Test
    void p6_057_aMissingOrDeletedDeviceIsNotFoundAndLeavesNothing() throws Exception {
        assertThat(anomalies.analyse(UUID.randomUUID(), Optional.empty(), Optional.empty(), Optional.empty()).deviceFound()).isFalse();
        mvc.perform(get("/api/v1/intelligence/anomalies/devices/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/intelligence/recommendations").param("deviceId", UUID.randomUUID().toString())).andExpect(status().isNotFound());
        UUID device = device();
        minuteSeries(device, "temperature", 41, i -> Double.MIN_VALUE);
        sql.update("DELETE FROM devices WHERE id = ?", device); // telemetry cascades with the device
        assertThat(anomalies.analyse(device, Optional.empty(), Optional.empty(), Optional.empty()).deviceFound()).isFalse();
        mvc.perform(get("/api/v1/intelligence/anomalies/devices/" + device)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/intelligence/anomalies/devices/" + UUID.randomUUID()).param("windowHours", "9999")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/intelligence/anomalies/devices/" + UUID.randomUUID()).param("capabilityKey", "Bad Key;DROP")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ recommendations from persisted Phase 3 facts

    @Test
    void p6_061_aThresholdNeverReachedByTheRecordedReadingsIsReportedWithItsSampleSizeAndAShortSeriesIsNot() {
        UUID device = device();
        minuteSeries(device, "temperature", 51, i -> Double.MIN_VALUE);
        lifecycle.legacySave(new Rule(UUID.randomUUID(), "Overheat", true, 5, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperatureAbove("40")), List.of(alert()), Map.of(), null), null);
        RecommendationService.Report rep = recommendations.recommend(Optional.of(device), Optional.empty(), Optional.empty(), Optional.empty());
        Recommendation t = rep.recommendations().stream().filter(x -> x.category() == Recommendation.Category.REVIEW_RULE_THRESHOLD).findFirst().orElseThrow();
        assertThat(t.status()).isEqualTo(Recommendation.Status.RECOMMENDED);
        assertThat(t.support().get("samples")).isEqualTo(24 * 60);
        assertThat(t.support().get("conditionTrueFor")).isEqualTo(0L);
        assertThat((Double) t.support().get("referenceValue")).isBetween(21.0, 23.0);
        assertThat(t.parameters().get("currentThreshold")).isEqualTo(40.0);
        assertThat(t.statement()).contains("1440 readings");
        assertThat(t.effect()).contains("nothing was changed");
        // a device with 30 readings: the same rule cannot be assessed
        UUID small = device();
        for (int i = 0; i < 30; i++) insert(small, "temperature", "NUMBER", now.minus(Duration.ofMinutes(60 - i)), 21.0 + (i % 5) * 0.1, "CELSIUS");
        lifecycle.legacySave(new Rule(UUID.randomUUID(), "Overheat small", true, 5, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, small.toString()), temperatureAbove("40")), List.of(alert()), Map.of(), null), null);
        Recommendation s = recommendations.recommend(Optional.of(small), Optional.empty(), Optional.empty(), Optional.empty()).recommendations().stream()
                .filter(x -> x.category() == Recommendation.Category.REVIEW_RULE_THRESHOLD).findFirst().orElseThrow();
        assertThat(s.status()).isEqualTo(Recommendation.Status.INSUFFICIENT_EVIDENCE);
        assertThat(s.support().get("samples")).isEqualTo(30);
    }

    @Test
    void p6_063_repeatedSuppressionBetweenTheSameRulesIsReportedFromRealArbitrationRecords() {
        UUID device = device();
        RuleCondition scope = new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString());
        UUID high = UUID.randomUUID(), low = UUID.randomUUID();
        lifecycle.legacySave(new Rule(high, "wins", true, 1, List.of(scope, temperatureAbove("20")), List.of(ledCommand(device, true)), Map.of(), null), null);
        lifecycle.legacySave(new Rule(low, "loses", true, 2, List.of(scope, temperatureAbove("20")), List.of(ledCommand(device, false)), Map.of(), null), null);
        Instant base = now;
        for (int i = 0; i < 3; i++) {
            now = base.plusSeconds(i + 1);
            ingest(device, 25, now);
        }
        now = base.plus(Duration.ofMinutes(5)); // move the evaluation past the last match: the window's upper bound is exclusive
        int persisted = count("SELECT count(*) FROM rule_action_candidates WHERE rule_id = ? AND disposition = 'SUPPRESSED_PRIORITY'", low);
        assertThat(persisted).as("the pipeline recorded at least the three ingestions as suppressions").isGreaterThanOrEqualTo(3);
        RecommendationService.Report rep = recommendations.recommend(Optional.empty(), Optional.of(low), Optional.empty(), Optional.empty());
        Recommendation c = rep.recommendations().stream().filter(x -> x.category() == Recommendation.Category.REVIEW_RULE_CONFLICT).findFirst().orElseThrow();
        assertThat(c.status()).isEqualTo(Recommendation.Status.RECOMMENDED);
        assertThat(c.support().get("suppressions")).as("the recommendation reports exactly what is persisted").isEqualTo(persisted);
        assertThat(c.subject().ruleId()).isEqualTo(low.toString());
        assertThat(c.evidenceRefs()).contains("rule:" + low, "rule:" + high);
        c.evidenceRefs().stream().filter(x -> x.startsWith("candidate:")).forEach(x ->
                assertThat(count("SELECT count(*) FROM rule_action_candidates WHERE candidate_id = ?::uuid AND disposition = 'SUPPRESSED_PRIORITY'", x.substring("candidate:".length()))).isEqualTo(1));
        // the winner has nothing suppressed
        assertThat(recommendations.recommend(Optional.empty(), Optional.of(high), Optional.empty(), Optional.empty()).recommendations()).noneMatch(x -> x.category() == Recommendation.Category.REVIEW_RULE_CONFLICT);
    }

    @Test
    void p6_064_maintenanceNeedsTwoIndependentFactGroupsAndAssertsNoPhysicalCondition() {
        UUID device = device();
        minuteSeries(device, "temperature", 61, i -> i >= RECENT_START ? 21.3 : Double.MIN_VALUE);          // group: unchanging telemetry
        sql.update("UPDATE device_connectivity SET status = 'OFFLINE', last_changed_at = ? WHERE device_id = ?", java.sql.Timestamp.from(now.minus(Duration.ofHours(30))), device); // group: offline 30 h
        Recommendation m = recommendations.recommend(Optional.of(device), Optional.empty(), Optional.empty(), Optional.empty()).recommendations().stream()
                .filter(x -> x.category() == Recommendation.Category.MAINTENANCE_CHECK).findFirst().orElseThrow();
        assertThat(m.support().get("independentFactGroups")).isEqualTo(2);
        assertThat(m.support().get("offlineHours")).isEqualTo(30L);
        assertThat(m.statement()).contains("Offline frequency is not available").contains("none of these facts states a physical condition");
        assertThat(m.statement().toLowerCase()).doesNotContain("worn").doesNotContain("damaged").doesNotContain("will fail");
        // one group alone is not enough
        UUID alone = device();
        minuteSeries(alone, "temperature", 62, i -> i >= RECENT_START ? 21.3 : Double.MIN_VALUE);
        assertThat(recommendations.recommend(Optional.of(alone), Optional.empty(), Optional.empty(), Optional.empty()).recommendations()).noneMatch(x -> x.category() == Recommendation.Category.MAINTENANCE_CHECK);
    }

    // ------------------------------------------------------------ no side effects, read-only, model optional

    @Test
    void p6_065_p6_071_producingRankingAndExplainingRecommendationsChangesNothingAndPublishesNothing() throws Exception {
        UUID device = device();
        minuteSeries(device, "temperature", 71, i -> i >= RECENT_START ? 21.3 : Double.MIN_VALUE);
        lifecycle.legacySave(new Rule(UUID.randomUUID(), "Overheat", true, 5, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperatureAbove("40")), List.of(alert()), Map.of(), null), null);
        resetPublicationSpies();
        Map<String, Long> before = footprint();
        reply.set("{\"ranking\":[\"R1\",\"R2\"],\"summary\":\"Two items to review.\"}");
        for (String url : List.of("/api/v1/intelligence/recommendations?deviceId=" + device, "/api/v1/intelligence/recommendations", "/api/v1/intelligence/recommendations?deviceId=" + device + "&explain=true",
                "/api/v1/intelligence/anomalies/devices/" + device))
            mvc.perform(get(url)).andExpect(status().isOk());
        assertThat(footprint()).isEqualTo(before);
        verifyNoPublication();
        org.mockito.Mockito.verifyNoInteractions(alerts);
        // GET only
        for (var req : List.of(post("/api/v1/intelligence/recommendations"), put("/api/v1/intelligence/recommendations"), delete("/api/v1/intelligence/recommendations"),
                post("/api/v1/intelligence/anomalies/devices/" + device)))
            assertThat(mvc.perform(req).andReturn().getResponse().getStatus()).isIn(404, 405);
        assertThat(footprint()).isEqualTo(before);
    }

    @Test
    void p6_065_theAnalysisRunsReadOnlyAndTheDatabaseRefusesAnyWriteInside() throws Exception {
        assertThat(AnomalyService.class.getMethod("analyse", UUID.class, Optional.class, Optional.class, Optional.class).getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly()).isTrue();
        assertThat(RecommendationService.class.getMethod("recommend", Optional.class, Optional.class, Optional.class, Optional.class).getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly()).isTrue();
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(s -> sql.update("UPDATE devices SET name = 'x' WHERE id = ?", UUID.randomUUID()))).hasMessageContaining("read-only transaction");
    }

    @Test
    void p6_067_noOutputCarriesAProbabilityOrConfidenceField() throws Exception {
        UUID device = device();
        minuteSeries(device, "temperature", 81, i -> i >= RECENT_START ? 21.3 : Double.MIN_VALUE);
        String body = mvc.perform(get("/api/v1/intelligence/recommendations").param("deviceId", device.toString())).andReturn().getResponse().getContentAsString();
        List<String> names = new ArrayList<>();
        collectNames(mapper.readTree(body), names);
        assertThat(names).isNotEmpty();
        assertThat(names.stream().map(String::toLowerCase)).noneMatch(n -> n.contains("probab") || n.contains("confiden") || n.contains("likelihood") || n.contains("score") && !n.equals("score"));
        assertThat(body.toLowerCase()).doesNotContain("\"confidence\"").doesNotContain("\"probability\"");
    }

    private static void collectNames(JsonNode n, List<String> out) {
        if (n.isObject()) n.fields().forEachRemaining(e -> { out.add(e.getKey()); collectNames(e.getValue(), out); });
        else if (n.isArray()) n.forEach(c -> collectNames(c, out));
    }

    @Test
    void p6_069_p6_068_theDeterministicResultStandsWithoutAModelAndAModelCanOnlyReorderOrSummariseGroundedFacts() throws Exception {
        UUID device = device();
        minuteSeries(device, "temperature", 91, i -> i >= RECENT_START ? 21.3 : Double.MIN_VALUE);
        sql.update("UPDATE device_connectivity SET status = 'OFFLINE', last_changed_at = ? WHERE device_id = ?", java.sql.Timestamp.from(now.minus(Duration.ofHours(30))), device);
        List<Recommendation> recs = recommendations.recommend(Optional.of(device), Optional.empty(), Optional.empty(), Optional.empty()).recommendations().stream().filter(r -> r.status() == Recommendation.Status.RECOMMENDED).toList();
        assertThat(recs.size()).isGreaterThanOrEqualTo(2);
        // provider unavailable: the explanation says so, the recommendations are unchanged
        when(provider.available()).thenReturn(false);
        RecommendationNarrator.Narration off = narrator.narrate(recs);
        assertThat(off.status()).isEqualTo("UNAVAILABLE");
        assertThat(off.reason()).isEqualTo("DISABLED");
        when(provider.available()).thenReturn(true);
        reply.set(null); // provider error
        assertThat(narrator.narrate(recs).reason()).isEqualTo("PROVIDER_ERROR");
        int n = recs.size();
        List<String> order = new ArrayList<>();
        for (int i = n; i >= 1; i--) order.add("\"R" + i + "\"");
        String reversed = "[" + String.join(",", order) + "]";
        // a grounded answer is accepted and only reorders
        String sentence = recs.getFirst().statement().split("(?<=[.!?])\\s+")[0];
        reply.set("{\"ranking\":" + reversed + ",\"summary\":" + mapper.writeValueAsString(sentence) + "}");
        RecommendationNarrator.Narration ok = narrator.narrate(recs);
        assertThat(ok.status()).isEqualTo("ACCEPTED");
        assertThat(ok.summary()).isEqualTo(sentence);
        assertThat(ok.rankedIds()).containsExactlyInAnyOrderElementsOf(recs.stream().map(Recommendation::id).toList());
        assertThat(ok.rankedIds().getFirst()).isEqualTo(recs.getLast().id());
        assertThat(ok.generatedBy().providerId()).isEqualTo("fake");
        // each kind of ungrounded answer is rejected and nothing from it is used
        String[][] bad = {
                {"{\"ranking\":[\"R1\"],\"summary\":\"x\"}", "RANKING_NOT_A_PERMUTATION"},
                {"{\"ranking\":" + reversed.replace("\"R1\"", "\"R9\"") + ",\"summary\":\"x\"}", "RANKING_NOT_A_PERMUTATION"},
                {"{\"ranking\":" + reversed + ",\"summary\":\"There is a 90% probability the sensor will fail soon.\"}", "UNSUPPORTED_SENTENCE"},
                {"{\"ranking\":" + reversed + ",\"summary\":\"The sensor is faulty.\"}", "UNSUPPORTED_SENTENCE"},
                {"{\"ranking\":" + reversed + ",\"summary\":\"The device reported 4721 readings.\"}", "UNSUPPORTED_SENTENCE"},
                {"{\"ranking\":" + reversed + ",\"summary\":\"" + "word ".repeat(150) + "\"}", "SUMMARY_TOO_LONG"}};
        for (String[] b : bad) {
            reply.set(b[0]);
            RecommendationNarrator.Narration rejected = narrator.narrate(recs);
            assertThat(rejected.status()).as(b[1]).isEqualTo("REJECTED");
            assertThat(rejected.reason()).isEqualTo(b[1]);
            assertThat(rejected.rankedIds()).isEmpty();
            assertThat(rejected.summary()).isNull();
        }
        reply.set("{\"ranking\":" + reversed + ",\"summary\":\"x\",\"newRecommendation\":\"reboot everything\"}");
        assertThat(narrator.narrate(recs).reason()).isEqualTo("OUTPUT_INVALID");
    }

    @Test
    void p6_062_commandHistoryIsReadOnTheDatabaseClockSoABackendClockThatLagsDoesNotHideRecentFailures() {
        UUID device = device();
        for (int i = 0; i < 3; i++) {
            UUID command = UUID.randomUUID();
            sql.update("INSERT INTO action_history(request_id, device_id, action_type, status, timestamp) VALUES (?,?,'SEND_COMMAND','FAILED', clock_timestamp())", command, device);
            sql.update("INSERT INTO command_state_history(command_id, sequence, fact_type, from_state, to_state, attempt, reason_code, source, occurred_at) VALUES (?,1,'DEAD_LETTERED','IN_FLIGHT','DEAD_LETTER',3,'RETRY_EXHAUSTED','DISPATCHER', clock_timestamp())", command);
        }
        // the backend clock is an hour BEHIND the database clock: a window bounded by backend time would end before these facts
        now = now.minus(Duration.ofHours(1));
        RecommendationService.Report report = recommendations.recommend(Optional.of(device), Optional.empty(), Optional.empty(), Optional.empty());
        Recommendation r = report.recommendations().stream().filter(x -> x.category() == Recommendation.Category.INVESTIGATE_FAILING_DEVICE).findFirst().orElseThrow();
        assertThat(r.status()).isEqualTo(Recommendation.Status.RECOMMENDED);
        assertThat(r.support().get("deadLettered")).isEqualTo(3);
        assertThat(report.commandFactWindow().to()).isAfter(report.evaluatedAt());
        assertThat(r.window()).isEqualTo(report.commandFactWindow());
    }

    @Test
    void p6_061_aSuggestedReferenceValueIsKeptInsideTheCapabilitysDeclaredRange() {
        UUID device = device();
        Random r = new Random(77);
        double[] v = new double[24 * 60];
        for (int i = 0; i < v.length; i++) v[i] = 23.0 + r.nextGaussian();            // 99th percentile is about 25
        minuteSeries(device, "temperature", 0, i -> Math.round(v[i] * 10) / 10.0);
        lifecycle.legacySave(new Rule(UUID.randomUUID(), "Overheat bounded", true, 5, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperatureAbove("40")), List.of(alert()), Map.of(), null), null);
        Object original = sql.queryForObject("SELECT maximum_value FROM capability_definitions WHERE capability_key = 'temperature'", Object.class);
        sql.update("UPDATE capability_definitions SET maximum_value = 22 WHERE capability_key = 'temperature'");
        try {
            Recommendation t = recommendations.recommend(Optional.of(device), Optional.empty(), Optional.empty(), Optional.empty()).recommendations().stream()
                    .filter(x -> x.category() == Recommendation.Category.REVIEW_RULE_THRESHOLD).findFirst().orElseThrow();
            assertThat((Double) t.support().get("referenceValue")).as("never above the declared maximum").isLessThanOrEqualTo(22.0).isEqualTo(22.0);
            assertThat(t.support().get("referenceClampedToCapabilityRange")).isEqualTo(true);
            assertThat(t.statement()).contains("kept within the capability's declared range as 22.0");
        } finally {
            sql.update("UPDATE capability_definitions SET maximum_value = ? WHERE capability_key = 'temperature'", original == null ? null : new java.math.BigDecimal(original.toString()));
        }
    }

    @Test
    void p6_070_aRequestsResultIsCappedPerScopeAndSaysSo() {
        UUID device = device();
        minuteSeries(device, "temperature", 95, i -> Double.MIN_VALUE);
        for (int i = 0; i < 52; i++)
            lifecycle.legacySave(new Rule(UUID.randomUUID(), "Overheat " + i, true, 5, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperatureAbove(String.valueOf(40 + i))), List.of(alert()), Map.of(), null), null);
        Map<String, Long> before = footprint();
        RecommendationService.Report report = recommendations.recommend(Optional.of(device), Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(report.recommendations()).hasSize(50);
        assertThat(report.truncated()).isTrue();
        assertThat(report.notices()).anyMatch(n -> n.contains("per-scope limit"));
        assertThat(footprint()).as("nothing is stored, so there is nothing to retain, expire or duplicate").isEqualTo(before);
    }

    @Test
    void p6_071_everyAnalysisBoundsItsStatementsAndRunsAlongsideIngestionWithoutInterfering() throws Exception {
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        String timeout = readOnly.execute(s -> {
            anomalies.analyse(UUID.randomUUID(), Optional.empty(), Optional.empty(), Optional.empty());
            return sql.queryForObject("SHOW statement_timeout", String.class);
        });
        assertThat(timeout).isEqualTo("5s");
        UUID busy = device();
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) batch.add(new Object[] {UUID.randomUUID(), busy, java.sql.Timestamp.from(now.minusSeconds(10L + 15L * (5_000 - i))), "TEMPERATURE", 21.0 + (i % 7) * 0.1, "CELSIUS", "temperature", "NUMBER", 21.0 + (i % 7) * 0.1});
        sql.batchUpdate("INSERT INTO telemetry_history(id, device_id, timestamp, sensor_type, value, unit, capability_key, value_type, numeric_value) VALUES (?,?,?,?,?,?,?,?,?)", batch);
        UUID ingesting = device();
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger analyses = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread analyst = new Thread(() -> {
            try {
                while (!stop.get() && analyses.get() < 200) {
                    anomalies.analyse(busy, Optional.empty(), Optional.of(30), Optional.empty());
                    analyses.incrementAndGet();
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        analyst.start();
        int before = count("SELECT count(*) FROM telemetry_history WHERE device_id = ?", ingesting);
        for (int i = 0; i < 30; i++) ingest(ingesting, 21 + (i % 5) * 0.1, now.minusSeconds(600 - i * 10));
        stop.set(true);
        analyst.join(60_000);
        assertThat(failure.get()).isNull();
        assertThat(count("SELECT count(*) FROM telemetry_history WHERE device_id = ?", ingesting)).as("every ingestion succeeded while analyses ran").isEqualTo(before + 30);
        assertThat(analyses.get()).isGreaterThan(0);
    }
}
