package com.nexus.intelligence.assistant;

import com.nexus.intelligence.assistant.AssistantAnswer.Kind;
import com.nexus.intelligence.assistant.AssistantAnswer.Statement;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6.12 through the assistant: usage questions are answered from stored power or energy readings only, with provenance and kind stated, advice that stays advice, and
 * an honest "cannot be reported" where no such reading exists. Real PostgreSQL.
 */
class AssistantEnergyIntegrationTest extends TypedTriggerSupport {
    @DynamicPropertySource
    static void assistantOn(DynamicPropertyRegistry r) {
        r.add("nexus.assistant.enabled", () -> "true");
        r.add("nexus.ai.rate-limit.requests-per-minute", () -> "1000");
        r.add("nexus.ai.rate-limit.burst", () -> "1000");
    }

    @Autowired AssistantService assistant;

    /** One database serves every test of the class: readings of an earlier test must not enter a fleet-wide figure. */
    @org.junit.jupiter.api.BeforeEach
    void noEarlierReadings() {
        sql.update("DELETE FROM telemetry_history");
    }

    private UUID device(String name, String mode) {
        UUID d = device();
        sql.update("UPDATE devices SET name=?, mode=? WHERE id=?", name, mode, d);
        return d;
    }

    private void hourOf(UUID device, String key, double value, String unit) {
        for (int i = 0; i <= 60; i++)
            sql.update("INSERT INTO telemetry_history(id, device_id, timestamp, sensor_type, value, unit, capability_key, value_type, numeric_value) VALUES (?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), device, Timestamp.from(now.minusSeconds(120 + (60 - i) * 60L)), key.toUpperCase(), value, unit, key, "NUMBER", value);
    }

    private static String text(AssistantAnswer a) {
        List<Statement> all = new ArrayList<>(a.statements());
        all.addAll(a.additional());
        return all.stream().map(Statement::text).collect(Collectors.joining(" | "));
    }

    private static void sourcesResolve(AssistantAnswer a) {
        Set<String> refs = a.sources().stream().map(e -> e.ref()).collect(Collectors.toSet());
        for (Statement s : a.statements()) if (s.kind() == Kind.FACT) {
            assertThat(s.refs()).as(s.text()).isNotEmpty();
            assertThat(refs).containsAll(s.refs());
        }
    }

    private String digest() {
        return sql.queryForObject("SELECT (SELECT count(*) FROM telemetry_history)||'/'||(SELECT count(*) FROM devices)||'/'||(SELECT count(*) FROM rules)||'/'||(SELECT count(*) FROM action_history)||'/'||(SELECT count(*) FROM command_outbox)||'/'||(SELECT count(*) FROM ai_rule_drafts)", String.class);
    }

    @Test
    void p6_221_p6_222_p6_224_aUsageQuestionIsAnsweredFromStoredReadingsWithProvenanceAndTheAnswerComesWithAdviceThatStaysAdvice() {
        UUID big = device("Boiler Kilo1", "REAL");
        UUID small = device("Lamp Lima2", "REAL");
        UUID sim = device("Fake Mike3", "SIMULATED");
        hourOf(big, "power", 3000.0, "W");
        hourOf(small, "power", 60.0, "W");
        hourOf(sim, "power", 500.0, "W");
        String before = digest();

        AssistantAnswer why = assistant.ask(null, "Why is our energy usage so high?");
        assertThat(why.status()).isEqualTo(AssistantAnswer.Status.ANSWERED);
        assertThat(why.plan()).containsExactly("ENERGY");
        String t = text(why);
        assertThat(t).contains("\"Boiler Kilo1\" (MEASURED): 3000 Wh (3 kWh)").contains("an ESTIMATE integrated from its power readings").contains("\"Lamp Lima2\" (MEASURED): 60 Wh")
                .contains("\"Fake Mike3\" (SIMULATED): 500 Wh").contains("Figures labelled SIMULATED come from simulated devices and are not real consumption");
        assertThat(t).contains("Simulated, metered and estimated figures are never added together").contains("No baseline of normal usage is stored, so the platform cannot say whether this usage is high");
        assertThat(t).contains("accounts for 98% of the MEASURED estimated energy");
        sourcesResolve(why);

        AssistantAnswer reduce = assistant.ask(null, "How can we reduce energy consumption?");
        assertThat(reduce.plan()).containsExactly("ENERGY");
        assertThat(text(reduce)).contains("Suggestion for a person to review (advice only, nothing was changed): \"Boiler Kilo1\"").contains("does not estimate savings");
        assertThat(text(reduce).toLowerCase()).doesNotContain("you will save").doesNotContain("saves ").doesNotContain("% less");
        sourcesResolve(reduce);

        AssistantAnswer one = assistant.ask(null, "How much energy did Lamp Lima2 use in the last 24 hours?");
        assertThat(one.plan()).containsExactly("ENERGY");
        assertThat(text(one)).contains("\"Lamp Lima2\" (MEASURED): 60 Wh (0.06 kWh)").doesNotContain("Boiler Kilo1");
        AssistantAnswer simulated = assistant.ask(null, "What is the electricity consumption of Fake Mike3?");
        assertThat(text(simulated)).contains("(SIMULATED)").contains("not real consumption");
        assertThat(digest()).as("nothing was written").isEqualTo(before);
    }

    @Test
    void p6_221_aDeviceWithNoPowerOrEnergyReadingGetsNoFigureAndNoGuess() {
        UUID d = device("Sensor November4", "REAL");
        hourOf(d, "temperature", 21.0, "CELSIUS");
        AssistantAnswer a = assistant.ask(null, "How much energy does Sensor November4 use?");
        assertThat(a.status()).isEqualTo(AssistantAnswer.Status.NO_EVIDENCE);
        String t = text(a);
        assertThat(t).contains("No power or energy readings are stored for \"Sensor November4\"").contains("energy use cannot be reported").contains("No figure is estimated from ratings or averages")
                .contains("needs a power or current sensor whose readings are stored");
        assertThat(t).doesNotContainPattern("\\d+(\\.\\d+)? (Wh|kWh|W)\\b");
    }

    @Test
    void p6_221_p6_223_anEnergyCounterIsReportedAsMeteredAndAGapIsReportedNotFilled() {
        UUID m = device("Meter Oscar5", "REAL");
        for (int i = 0; i <= 60; i++)
            sql.update("INSERT INTO telemetry_history(id, device_id, timestamp, sensor_type, value, unit, capability_key, value_type, numeric_value) VALUES (?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), m, Timestamp.from(now.minusSeconds(120 + (60 - i) * 60L)), "ENERGY", 5.0 + i * 0.01, "kWh", "energy", "NUMBER", 5.0 + i * 0.01);
        AssistantAnswer metered = assistant.ask(null, "How much energy did Meter Oscar5 consume?");
        assertThat(text(metered)).contains("\"Meter Oscar5\" (MEASURED): 600 Wh (0.6 kWh)").contains("metered by the device's own energy counter").contains("Across 1 MEASURED device(s), the metered energy was 600 Wh");
        sourcesResolve(metered);
        UUID g = device("Pump Papa6", "REAL");
        for (int i = 0; i < 20; i++) sql.update("INSERT INTO telemetry_history(id, device_id, timestamp, sensor_type, value, unit, capability_key, value_type, numeric_value) VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), g, Timestamp.from(now.minusSeconds(20_000 + i * 60L)), "POWER", 100.0, "W", "power", "NUMBER", 100.0);
        for (int i = 0; i < 20; i++) sql.update("INSERT INTO telemetry_history(id, device_id, timestamp, sensor_type, value, unit, capability_key, value_type, numeric_value) VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), g, Timestamp.from(now.minusSeconds(2_000 + i * 60L)), "POWER", 100.0, "W", "power", "NUMBER", 100.0);
        AssistantAnswer gap = assistant.ask(null, "What is the energy usage of Pump Papa6?");
        assertThat(text(gap)).contains("were NOT integrated").contains("the power during them is unknown").contains("readings cover");
    }

    @Test
    void p6_224_anEnergyQuestionNeverTurnsIntoACommandOrADraft() {
        device("Heater Quebec7", "REAL");
        String before = digest();
        assertThat(assistant.ask(null, "Switch off Heater Quebec7 to save energy").status()).isEqualTo(AssistantAnswer.Status.REFUSED_WRITE);
        assertThat(assistant.ask(null, "Reduce the energy usage by turning everything off").status()).isEqualTo(AssistantAnswer.Status.REFUSED_WRITE);
        assertThat(digest()).isEqualTo(before);
    }
}
