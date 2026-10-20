package com.nexus.intelligence.energy;

import com.nexus.intelligence.energy.EnergyResult.Kind;
import com.nexus.intelligence.energy.EnergyResult.Status;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P6.12 on real PostgreSQL: energy only from stored power or energy readings, provenance kept apart, gaps reported, nothing estimated from ratings, nothing written. */
@AutoConfigureMockMvc
class EnergyIntegrationTest extends TypedTriggerSupport {
    @DynamicPropertySource
    static void limits(DynamicPropertyRegistry r) {
        r.add("nexus.ai.rate-limit.requests-per-minute", () -> "1000");
        r.add("nexus.ai.rate-limit.burst", () -> "1000");
    }

    @Autowired EnergyService energy;
    @Autowired MockMvc mvc;

    private UUID device(String name, String mode) {
        UUID d = device();
        sql.update("UPDATE devices SET name=?, mode=? WHERE id=?", name, mode, d);
        return d;
    }

    private void reading(UUID device, String key, Instant at, double value, String unit) {
        sql.update("INSERT INTO telemetry_history(id, device_id, timestamp, sensor_type, value, unit, capability_key, value_type, numeric_value) VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), device, Timestamp.from(at), key.toUpperCase(), value, unit, key, "NUMBER", value);
    }

    /** One reading a minute for an hour ending a minute ago. */
    private void hourOf(UUID device, String key, double value, String unit) {
        for (int i = 0; i <= 60; i++) reading(device, key, now.minusSeconds(120 + (60 - i) * 60L), value, unit);
    }

    private String digest() {
        return sql.queryForObject("SELECT (SELECT count(*) FROM telemetry_history)||'/'||(SELECT count(*) FROM devices)||'/'||(SELECT count(*) FROM rules)||'/'||(SELECT count(*) FROM action_history)||'/'||(SELECT count(*) FROM command_outbox)||'/'||(SELECT count(*) FROM ai_rule_drafts)", String.class);
    }

    private static EnergyService.DeviceEnergy row(EnergyService.Report r, UUID id) {
        return r.devices().stream().filter(d -> d.deviceId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void p6_221_p6_222_energyComesOnlyFromStoredReadingsWithProvenanceAndKindKeptApartAndTotalsNeverMixed() {
        UUID real = device("Heater Alpha1", "REAL");
        UUID simulated = device("Heater Bravo2", "SIMULATED");
        UUID metered = device("Meter Charlie3", "REAL");
        UUID noPower = device("Sensor Delta4", "REAL");
        hourOf(real, "power", 100.0, "W");
        hourOf(simulated, "power", 0.2, "kW");
        for (int i = 0; i <= 60; i++) reading(metered, "energy", now.minusSeconds(120 + (60 - i) * 60L), 5.0 + 0.5 * i / 60.0, "kWh");
        hourOf(noPower, "temperature", 21.0, "CELSIUS");
        String before = digest();

        EnergyService.Report fleet = energy.analyse(Optional.empty(), Optional.of(24));
        assertThat(fleet.available()).isTrue();
        assertThat(fleet.deviceFound()).isTrue();
        EnergyService.DeviceEnergy a = row(fleet, real), b = row(fleet, simulated), m = row(fleet, metered);
        assertThat(a.provenance()).isEqualTo("MEASURED");
        assertThat(a.result().kind()).isEqualTo(Kind.INTEGRATED_FROM_POWER);
        assertThat(a.result().energyWh()).isCloseTo(100.0, within(1e-6));
        assertThat(b.provenance()).isEqualTo("SIMULATED");
        assertThat(b.result().energyWh()).isCloseTo(200.0, within(1e-6));
        assertThat(m.result().kind()).isEqualTo(Kind.METERED_COUNTER);
        assertThat(m.result().energyWh()).isCloseTo(500.0, within(1e-6));
        assertThat(fleet.devices().stream().map(EnergyService.DeviceEnergy::deviceId)).as("a device with only a temperature has no energy figure").doesNotContain(noPower);
        // each (provenance, kind) group is totalled alone: no sum across them exists
        assertThat(fleet.totals()).extracting(t -> t.provenance() + "/" + t.kind()).contains("MEASURED/INTEGRATED_FROM_POWER", "SIMULATED/INTEGRATED_FROM_POWER", "MEASURED/METERED_COUNTER");
        EnergyService.Total measuredEstimate = fleet.totals().stream().filter(t -> t.provenance().equals("MEASURED") && t.kind() == Kind.INTEGRATED_FROM_POWER).findFirst().orElseThrow();
        assertThat(measuredEstimate.energyWh()).isGreaterThanOrEqualTo(100.0);
        assertThat(fleet.topConsumers().get(0).result().energyWh()).isGreaterThanOrEqualTo(fleet.topConsumers().get(fleet.topConsumers().size() - 1).result().energyWh());
        assertThat(a.shareOfGroup()).isBetween(0.0, 1.0);
        assertThat(digest()).as("the analysis writes nothing").isEqualTo(before);
    }

    @Test
    void p6_221_aDeviceWithoutPowerOrEnergyReadingsHasNoFigureAndTheReportSaysWhatIsNeeded() {
        UUID d = device("Sensor Echo5", "REAL");
        hourOf(d, "temperature", 21.0, "CELSIUS");
        EnergyService.Report r = energy.analyse(Optional.of(d), Optional.of(24));
        assertThat(r.deviceFound()).isTrue();
        assertThat(r.available()).isFalse();
        assertThat(r.devices()).isEmpty();
        assertThat(r.totals()).isEmpty();
        assertThat(r.unavailableReason()).contains("no power or energy reading").contains("power").contains("energy");
        assertThat(energy.analyse(Optional.of(UUID.randomUUID()), Optional.empty()).deviceFound()).isFalse();
    }

    @Test
    void p6_223_irregularSamplingAMixOfUnitsAndTheWindowBoundaryAreHandledWithoutInventingValues() {
        UUID gap = device("Pump Foxtrot6", "REAL");
        for (int i = 0; i < 30; i++) reading(gap, "power", now.minusSeconds(7200 + (30 - i) * 60L), 150.0, "W");          // 30 minutes of readings, then nothing for an hour
        for (int i = 0; i < 30; i++) reading(gap, "power", now.minusSeconds(1800 + (30 - i) * 60L), 150.0, "W");
        EnergyResult r = row(energy.analyse(Optional.of(gap), Optional.of(24)), gap).result();
        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.gaps()).isEqualTo(1);
        assertThat(r.coverage()).isLessThan(0.6);
        assertThat(r.energyWh()).as("only the two sampled stretches, 29 minutes each at 150 W").isCloseTo(150.0 * 2 * 29 * 60 / 3600.0, within(1e-6));

        UUID mixed = device("Pump Golf7", "REAL");
        reading(mixed, "power", now.minusSeconds(300), 100, "W");
        reading(mixed, "power", now.minusSeconds(240), 0.1, "kW");
        EnergyResult m = row(energy.analyse(Optional.of(mixed), Optional.empty()), mixed).result();
        assertThat(m.status()).isEqualTo(Status.NOT_APPLICABLE);
        assertThat(m.reason()).contains("mix units");

        UUID edge = device("Pump Hotel8", "REAL");
        reading(edge, "power", now.minus(java.time.Duration.ofHours(24)), 100, "W");                 // exactly on the lower window boundary: included
        reading(edge, "power", now.minus(java.time.Duration.ofHours(24)).plusSeconds(60), 100, "W");
        reading(edge, "power", now.minus(java.time.Duration.ofHours(24)).minusSeconds(60), 999, "W"); // before the window: excluded
        EnergyResult e = row(energy.analyse(Optional.of(edge), Optional.of(24)), edge).result();
        assertThat(e.samples()).isEqualTo(2);
        assertThat(e.peakPowerW()).isEqualTo(100.0);
    }

    @Test
    void p6_224_theDeviceBoundIsReportedAndTheHttpRouteAnswersAndRefusesBadInput() throws Exception {
        UUID d = device("Fan India9", "REAL");
        hourOf(d, "power", 50.0, "W");
        mvc.perform(get("/api/v1/intelligence/energy").param("deviceId", d.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.devices[0].provenance").value("MEASURED")).andExpect(jsonPath("$.devices[0].result.kind").value("INTEGRATED_FROM_POWER"));
        mvc.perform(get("/api/v1/intelligence/energy").param("deviceId", "nope")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/intelligence/energy").param("windowHours", "100000")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/intelligence/energy").param("deviceId", UUID.randomUUID().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.deviceFound").value(false));
        List<UUID> many = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID x = device("Bulk Device " + i + "Z9", "REAL");
            hourOf(x, "power", 10.0 + i, "W");
            many.add(x);
        }
        EnergyService small = new EnergyService(new EnergyReader(sql), new com.nexus.intelligence.anomaly.TelemetrySeriesReader(sql),
                new EnergySettings(24, List.of("power"), List.of("energy"), 5, 60, 21_600, 5_000, 3, 2, 300), clock);
        assertThat(small.analyse(Optional.empty(), Optional.of(24)).devices().size()).isLessThanOrEqualTo(3);
        assertThat(small.analyse(Optional.empty(), Optional.of(24)).candidatesTruncated()).isTrue();
        assertThat(small.analyse(Optional.empty(), Optional.of(24)).topConsumers().size()).isLessThanOrEqualTo(2);
    }
}
