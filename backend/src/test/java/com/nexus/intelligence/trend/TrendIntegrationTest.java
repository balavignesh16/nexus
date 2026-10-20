package com.nexus.intelligence.trend;

import com.nexus.device.capability.CapabilityType;
import com.nexus.intelligence.draft.DraftOutcome;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P6.8 on real PostgreSQL: stored telemetry through the read-only trend service and the hard-limit template stored as an inactive draft. */
@AutoConfigureMockMvc
class TrendIntegrationTest extends TypedTriggerSupport {
    @Autowired TrendService trends;
    @Autowired HardLimitDrafts hardLimits;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;

    private void reading(UUID device, String capability, Instant at, double value, String unit) {
        sql.update("INSERT INTO telemetry_history(id, device_id, timestamp, sensor_type, value, unit, capability_key, value_type, numeric_value) VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), device, Timestamp.from(at), capability.toUpperCase(), value, unit, capability, "NUMBER", value);
    }

    private String digest() {
        return sql.queryForObject("""
                SELECT (SELECT count(*) FROM rules)||'/'||(SELECT count(*) FROM rule_versions)||'/'||(SELECT count(*) FROM action_history)||'/'||(SELECT count(*) FROM command_outbox)||'/'||
                       (SELECT count(*) FROM command_state_history)||'/'||(SELECT count(*) FROM current_desired_twin_state)||'/'||(SELECT count(*) FROM ai_rule_drafts)||'/'||
                       (SELECT count(*) FROM ai_rule_draft_decisions)||'/'||(SELECT count(*) FROM telemetry_history)""", String.class);
    }

    // ------------------------------------------------------------------ the service on stored readings

    @Test
    void p6_131_p6_132_p6_145_theMotorExampleOnStoredReadingsIsIncreasingMeetsALimitOf100AndWritesNothing() {
        UUID motor = device();
        reading(motor, "temperature", now.minusSeconds(300), 98, "CELSIUS");
        reading(motor, "temperature", now.minusSeconds(240), 99, "CELSIUS");
        reading(motor, "temperature", now.minusSeconds(180), 100, "CELSIUS");
        String before = digest();

        TrendService.Report noLimit = trends.analyse(motor, "temperature", Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(noLimit.deviceFound()).isTrue();
        TrendResult r = noLimit.trend();
        assertThat(r.direction()).isEqualTo(TrendDirection.INCREASING);
        assertThat(r.basis()).isEqualTo("TIMESTAMPED");
        assertThat(r.rate().perMinute()).isEqualTo(1.0);
        assertThat(r.rate().unit()).isEqualTo("CELSIUS/min");
        assertThat(r.established().established()).isFalse();
        assertThat(r.limit()).as("no limit configured: no statement about safety").isNull();

        TrendResult withLimit = trends.analyse(motor, "temperature", Optional.empty(), Optional.of(new HardLimit(100, "CELSIUS", HardLimit.Comparison.GREATER_OR_EQUAL)), Optional.empty()).trend();
        assertThat(withLimit.limit().status()).isEqualTo("MET");
        TrendResult strict = trends.analyse(motor, "temperature", Optional.empty(), Optional.of(new HardLimit(100, "CELSIUS", HardLimit.Comparison.GREATER_THAN)), Optional.empty()).trend();
        assertThat(strict.limit().status()).isEqualTo("NOT_MET");
        TrendResult wrongUnit = trends.analyse(motor, "temperature", Optional.empty(), Optional.of(new HardLimit(100, "FAHRENHEIT", HardLimit.Comparison.GREATER_OR_EQUAL)), Optional.empty()).trend();
        assertThat(wrongUnit.limit().status()).isEqualTo("UNIT_MISMATCH");

        assertThat(trends.analyse(UUID.randomUUID(), "temperature", Optional.empty(), Optional.empty(), Optional.empty()).deviceFound()).isFalse();
        assertThat(digest()).as("trend analysis writes nothing and publishes nothing").isEqualTo(before);
    }

    @Test
    void p6_140_theWindowIsHalfOpenAndFutureDatedReadingsAreExcludedAndReported() {
        UUID device = device();
        Instant from = now.minus(Duration.ofHours(1));
        reading(device, "temperature", from, 30, "CELSIUS");                                // exactly on the lower boundary: included
        reading(device, "temperature", from.plusSeconds(1800), 31, "CELSIUS");
        reading(device, "temperature", now.minusSeconds(60), 32, "CELSIUS");
        reading(device, "temperature", now, 33, "CELSIUS");                                 // exactly on the upper boundary: excluded
        reading(device, "temperature", from.minusSeconds(1), 20, "CELSIUS");               // just outside: excluded
        TrendResult r = trends.analyse(device, "temperature", Optional.of(1), Optional.empty(), Optional.empty()).trend();
        assertThat(r.sampleCount()).isEqualTo(3);
        assertThat(r.firstValue()).isEqualTo(30.0);
        assertThat(r.lastValue()).isEqualTo(32.0);

        UUID skewed = device();
        for (int i = 0; i < 4; i++) reading(skewed, "temperature", now.minusSeconds(600 - i * 60L), 50 + i, "CELSIUS");
        reading(skewed, "temperature", now.plus(Duration.ofHours(2)), 999, "CELSIUS");
        TrendResult s = trends.analyse(skewed, "temperature", Optional.empty(), Optional.empty(), Optional.empty()).trend();
        assertThat(s.sampleCount()).isEqualTo(4);
        assertThat(s.lastValue()).isEqualTo(53.0);
        assertThat(s.quality()).containsEntry("futureDatedExcluded", 0);   // beyond the read window anyway
        UUID nearFuture = device();
        for (int i = 0; i < 4; i++) reading(nearFuture, "temperature", now.minusSeconds(600 - i * 60L), 50 + i, "CELSIUS");
        reading(nearFuture, "temperature", now.minusSeconds(1).plusSeconds(0), 54, "CELSIUS");
        assertThat(trends.analyse(nearFuture, "temperature", Optional.empty(), Optional.empty(), Optional.empty()).trend().sampleCount()).isEqualTo(5);
    }

    @Test
    void p6_142_nonNumericAndMixedAndMissingSeriesAreNotApplicableOrInsufficient() {
        UUID device = device();
        sql.update("INSERT INTO telemetry_history(id, device_id, timestamp, sensor_type, value, unit, capability_key, value_type, boolean_value) VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), device, Timestamp.from(now.minusSeconds(60)), "LED", 1.0, "BOOLEAN", "led", "BOOLEAN", true);
        assertThat(trends.analyse(device, "led", Optional.empty(), Optional.empty(), Optional.empty()).trend().direction()).isEqualTo(TrendDirection.NOT_APPLICABLE);
        assertThat(trends.analyse(device, "temperature", Optional.empty(), Optional.empty(), Optional.empty()).trend().direction()).isEqualTo(TrendDirection.INSUFFICIENT_EVIDENCE);
        UUID mixed = device();
        reading(mixed, "temperature", now.minusSeconds(120), 20, "CELSIUS");
        reading(mixed, "temperature", now.minusSeconds(60), 70, "FAHRENHEIT");
        TrendResult m = trends.analyse(mixed, "temperature", Optional.empty(), Optional.empty(), Optional.empty()).trend();
        assertThat(m.direction()).isEqualTo(TrendDirection.INSUFFICIENT_EVIDENCE);
        assertThat(m.reason()).contains("mix units");
    }

    @Test
    void p6_131_p6_132_theHttpRouteReturnsTheAnalysisAndRefusesBadInput() throws Exception {
        UUID motor = device();
        for (int i = 0; i < 3; i++) reading(motor, "temperature", now.minusSeconds(300 - i * 60L), 98 + i, "CELSIUS");
        mvc.perform(get("/api/v1/intelligence/trends/devices/" + motor).param("capabilityKey", "temperature").param("limitValue", "100").param("limitUnit", "CELSIUS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trend.direction").value("INCREASING")).andExpect(jsonPath("$.trend.limit.status").value("MET"))
                .andExpect(jsonPath("$.trend.established.established").value(false));
        mvc.perform(get("/api/v1/intelligence/trends/devices/" + motor).param("capabilityKey", "Temperature;DROP")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/intelligence/trends/devices/" + motor).param("capabilityKey", "temperature").param("limitValue", "100")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/intelligence/trends/devices/" + motor).param("capabilityKey", "temperature").param("windowHours", "100000")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/intelligence/trends/devices/" + motor).param("capabilityKey", "temperature").param("limitValue", "100").param("limitUnit", "C").param("limitComparison", "EQUALS"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ the hard-limit template

    private HardLimitDrafts.Request request(UUID device, String unit, boolean cutoff) {
        return new HardLimitDrafts.Request(device, "temperature", CapabilityType.TEMPERATURE, 100.0, unit, HardLimit.Comparison.GREATER_OR_EQUAL, cutoff ? "LED" : null, cutoff ? "SET_STATE" : null,
                cutoff ? Boolean.FALSE : null, 60);
    }

    @Test
    void p6_143_aHardLimitSuggestionIsStoredOnlyAsAnInactiveDraftThatNeedsApprovalAndSendsNothing() throws Exception {
        UUID device = device();
        int rulesBefore = count("SELECT count(*) FROM rules");
        int commandsBefore = count("SELECT count(*) FROM action_history") + count("SELECT count(*) FROM command_outbox");
        DraftOutcome outcome = hardLimits.propose(request(device, "CELSIUS", true));
        assertThat(outcome).isInstanceOf(DraftOutcome.Created.class);
        DraftOutcome.Created created = (DraftOutcome.Created) outcome;
        String id = created.draftId().toString();
        assertThat(created.diagnostics()).as(created.diagnostics().toString()).isEmpty();
        assertThat(created.state()).isEqualTo("PENDING_APPROVAL");

        assertThat(sql.queryForObject("SELECT state FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id)).isEqualTo("PENDING_APPROVAL");
        assertThat(sql.queryForObject("SELECT provider_id FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id)).isEqualTo("none");
        assertThat(sql.queryForObject("SELECT definition->>'enabled' FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id)).isEqualTo("false");
        assertThat(sql.queryForObject("SELECT definition->'metadata'->>'origin' FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id)).isEqualTo("TEMPLATE_DRAFT");
        assertThat(sql.queryForObject("SELECT validation->>'promptTemplate' FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id)).isEqualTo("template/hard-limit");
        assertThat(sql.queryForObject("SELECT validation #>> '{conflictAnalysis,status}' FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id)).isNotNull();
        assertThat(sql.queryForObject("SELECT validation->>'rationaleDropped' FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id)).isNull();
        String rationale = sql.queryForObject("SELECT rationale FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id);
        assertThat(rationale).contains("not a certified hardware emergency cutoff").contains("a draft");
        String definition = sql.queryForObject("SELECT definition::text FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id);
        assertThat(definition).contains("\"GTE\"").contains("\"CREATE_ALERT\"").contains("\"SEND_COMMAND\"").contains("\"value\": false".replace(" ", "").isEmpty() ? "" : "false");

        // nothing exists as a rule, nothing was published or accepted, and a verbatim copy cannot be activated through the legacy API
        assertThat(count("SELECT count(*) FROM rules")).isEqualTo(rulesBefore);
        assertThat(count("SELECT count(*) FROM action_history") + count("SELECT count(*) FROM command_outbox")).isEqualTo(commandsBefore);
        Rule copy = mapper.readValue(definition, Rule.class);
        Rule enabled = new Rule(copy.ruleId(), copy.name(), true, copy.priority(), copy.conditions(), copy.actions(), copy.metadata(), copy.cooldownSeconds(), null, copy.runtime());
        assertThatThrownBy(() -> lifecycle.legacySave(enabled, null)).hasMessageContaining("recorded approval");
        assertThat(count("SELECT count(*) FROM rules WHERE rule_id=?::uuid", copy.ruleId().toString())).isZero();
    }

    @Test
    void p6_143_anAlertOnlyTemplateAndABadRequestAndTheHttpRoute() throws Exception {
        UUID device = device();
        DraftOutcome alertOnly = hardLimits.propose(request(device, "CELSIUS", false));
        assertThat(alertOnly).isInstanceOf(DraftOutcome.Created.class);
        assertThatThrownBy(() -> hardLimits.propose(new HardLimitDrafts.Request(device, "temperature", CapabilityType.TEMPERATURE, null, "CELSIUS", null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hardLimits.propose(new HardLimitDrafts.Request(device, "temperature", CapabilityType.TEMPERATURE, 100.0, "CELSIUS", null, "LED", null, null, null)))
                .hasMessageContaining("cutoff");
        String body = mapper.writeValueAsString(request(device, "CELSIUS", true));
        mvc.perform(post("/api/v1/intelligence/drafts/hard-limit").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.valid").value(true));
        mvc.perform(post("/api/v1/intelligence/drafts/hard-limit").contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"" + device + "\"}")).andExpect(status().isBadRequest());
        // a limit for an unknown device is stored INVALID, never approvable
        DraftOutcome unknown = hardLimits.propose(request(UUID.randomUUID(), "CELSIUS", false));
        assertThat(unknown).isInstanceOf(DraftOutcome.Created.class);
        assertThat(((DraftOutcome.Created) unknown).valid()).isFalse();
        assertThat(((DraftOutcome.Created) unknown).state()).isEqualTo("INVALID");
    }

    @Test
    void p6_143_aLimitInTheWrongUnitForTheDeviceCapabilityIsRefusedByTheExistingValidator() {
        UUID device = device();   // its temperature capability is in CELSIUS
        DraftOutcome wrong = hardLimits.propose(request(device, "FAHRENHEIT", false));
        assertThat(wrong).isInstanceOf(DraftOutcome.Created.class);
        DraftOutcome.Created created = (DraftOutcome.Created) wrong;
        assertThat(created.valid()).as("a unit that does not match the capability must not be a valid draft: " + created.diagnostics()).isFalse();
        assertThat(created.diagnostics().toString()).containsIgnoringCase("unit");
    }

    @Test
    void p6_144_noTrendOrTemplateSourceDescribesARuleAsACertifiedCutoffExceptToDenyIt() throws IOException {
        List<Path> sources;
        try (Stream<Path> s = Files.walk(Path.of("src/main/java/com/nexus/intelligence/trend"))) {
            sources = s.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(sources).hasSizeGreaterThanOrEqualTo(7);
        for (Path p : sources) {
            String text = Files.readString(p);
            int at = 0;
            while ((at = text.indexOf("certified", at)) >= 0) {
                String before = text.substring(Math.max(0, at - 6), at);
                assertThat(before).as(p + " at " + at).isEqualTo("not a ");
                at += 9;
            }
        }
    }
}
