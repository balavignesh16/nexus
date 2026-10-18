package com.nexus.action.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.intelligence.recommend.Recommendation;
import com.nexus.intelligence.recommend.RecommendationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P6-062: the failing-device recommendation is built from REAL Phase 4 facts. The commands are accepted by the real service and dead-lettered by the real
 * dispatcher against a broker that refuses them; the recommendation counts and cites those persisted facts, ranks the device, treats too few failures as
 * insufficient evidence, and changes and publishes nothing.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"nexus.ai.rate-limit.requests-per-minute=6000", "nexus.ai.rate-limit.burst=6000"})
class FailureRecommendationIntegrationTest extends LifecycleFixture {
    @Autowired RecommendationService recommendations;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;


    /** The history fact is appended just after the outbox state flips to DEAD_LETTER: wait for the persisted facts the assertion depends on. */
    private void awaitDeadLetterFacts(UUID device, int expected) throws InterruptedException {
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM command_state_history h JOIN action_history a ON a.request_id = h.command_id WHERE a.device_id = ? AND h.fact_type = 'DEAD_LETTERED'", Integer.class, device) == expected,
                expected + " DEAD_LETTERED fact(s) for the device", 60);
    }

    private Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new String[] {"action_history", "command_outbox", "command_state_history", "pipeline_traces", "rule_matches", "rule_action_candidates", "digital_twins", "telemetry_history",
                "current_desired_twin_state", "current_twin_verification_state", "devices", "rules", "rule_versions", "ai_rule_drafts", "device_connectivity"})
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        return counts;
    }

    @Test
    @Timeout(600)
    void p6_062_repeatedDeadLettersAreRankedFromPersistedPhase4FactsAndEveryReferenceResolves() throws Exception {
        UUID failing = deviceId;
        UUID other = newDevice();
        for (int i = 0; i < 3; i++) sendDeadLettered(BrokerCommandOutcome.NOT_SENT);
        UUID saved = deviceId;
        deviceId = other;
        sendDeadLettered(BrokerCommandOutcome.NOT_SENT);
        deviceId = saved;
        awaitDeadLetterFacts(failing, 3);
        awaitDeadLetterFacts(other, 1);

        RecommendationService.Report report = recommendations.recommend(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        Recommendation a = report.recommendations().stream().filter(r -> r.category() == Recommendation.Category.INVESTIGATE_FAILING_DEVICE && failing.toString().equals(r.subject().deviceId())).findFirst().orElseThrow();
        Recommendation b = report.recommendations().stream().filter(r -> r.category() == Recommendation.Category.INVESTIGATE_FAILING_DEVICE && other.toString().equals(r.subject().deviceId())).findFirst().orElseThrow();
        assertThat(a.status()).isEqualTo(Recommendation.Status.RECOMMENDED);
        assertThat(a.support().get("deadLettered")).isEqualTo(3);
        assertThat(a.support().get("total")).isEqualTo(3);
        assertThat(a.window().to()).isAfter(a.window().from());
        assertThat(b.status()).as("one failure is below the stated minimum").isEqualTo(Recommendation.Status.INSUFFICIENT_EVIDENCE);
        assertThat(b.support().get("total")).isEqualTo(1);
        assertThat((int) a.support().get("rankByTotal")).isLessThan((int) b.support().get("rankByTotal"));
        assertThat(a.statement()).contains("do not by themselves show whether the fault is in the device");
        for (String ref : a.evidenceRefs()) {
            String[] parts = ref.substring("command:".length()).split("/fact/");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_state_history h JOIN action_history x ON x.request_id = h.command_id WHERE h.command_id = ?::uuid AND h.sequence = ? AND h.fact_type = 'DEAD_LETTERED' AND x.device_id = ?",
                    Integer.class, parts[0], Integer.parseInt(parts[1]), failing)).as(ref).isEqualTo(1);
        }
        assertThat(a.evidenceRefs()).hasSize(3);
    }

    @Test
    @Timeout(300)
    void p6_062_p6_065_asking_repeatedly_changesNothingPublishesNothingAndGivesTheSameRecommendations() throws Exception {
        for (int i = 0; i < 3; i++) sendDeadLettered(BrokerCommandOutcome.NOT_SENT);
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) == 0, "quiet outbox", 60);
        awaitDeadLetterFacts(deviceId, 3);
        int calls = broker.calls.size();
        Map<String, Long> before = counts();
        List<Recommendation> first = recommendations.recommend(Optional.of(deviceId), Optional.empty(), Optional.empty(), Optional.empty()).recommendations();
        List<Recommendation> second = recommendations.recommend(Optional.of(deviceId), Optional.empty(), Optional.empty(), Optional.empty()).recommendations();
        assertThat(second.stream().map(Recommendation::id).toList()).isEqualTo(first.stream().map(Recommendation::id).toList());
        assertThat(first).anyMatch(r -> r.category() == Recommendation.Category.INVESTIGATE_FAILING_DEVICE && r.status() == Recommendation.Status.RECOMMENDED);
        String body = mvc.perform(get("/api/v1/intelligence/recommendations").param("deviceId", deviceId.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode recs = json.readTree(body).get("report").get("recommendations");
        assertThat(recs).isNotEmpty();
        recs.forEach(r -> assertThat(r.get("effect").asText()).contains("nothing was changed"));
        assertThat(counts()).isEqualTo(before);
        assertThat(broker.calls).as("no publication").hasSize(calls);
    }
}
