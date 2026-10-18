package com.nexus.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.outbox.OutboxPayload;
import com.nexus.adapter.core.AdapterRegistry;
import com.nexus.adapter.core.CommandEgressRouter;
import com.nexus.adapter.core.SimulatorTransportEligibility;
import com.nexus.adapter.simulator.FirmwareLikeDevice;
import com.nexus.adapter.simulator.SimulatedDevice;
import com.nexus.adapter.simulator.SimulatorAdapter;
import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.Attempt;
import com.nexus.adapter.spi.CommandEnvelope;
import com.nexus.adapter.spi.Eligibility;
import com.nexus.adapter.spi.EvidenceKind;
import com.nexus.adapter.spi.IntakeResult;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryIntake;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.adapter.spi.TransportContext;
import com.nexus.adapter.spi.TransportEligibility;
import com.nexus.adapter.spi.TransportKind;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The in-process simulator transport on the real application and PostgreSQL with NO broker (ledger P5-090, P5-092): telemetry, command delivery
 * and acknowledgements go through the same ports as any adapter, a device is served by its transport and reported by its mode, and the
 * transport is fail-closed for command acceptance because the dispatcher is not routed through the adapter egress yet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SimulatorAdapterIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("mqtt.broker.url", () -> "tcp://127.0.0.1:1");   // there is no broker in this test, on purpose
        registry.add("mqtt.client.id", () -> "p55-app-" + UUID.randomUUID());
        registry.add("nexus.adapter.simulator.enabled", () -> "true");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SimulatorAdapter simulator;
    @Autowired AdapterRegistry registry;
    @Autowired CommandEgressRouter router;
    @Autowired TransportEligibility eligibility;
    @Autowired TelemetryIntake mqttStyleIntake;
    @Autowired MqttClient mqttClient;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;

    private UUID device(DeviceMode mode, TransportKind transport) {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Sim", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "test", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "test", now, now));
        Device d = new Device(UUID.randomUUID(), space, "M", DeviceType.CUSTOM, "NEXUS", "TEST", "P55-" + UUID.randomUUID(), null, mode);
        d.update("M", DeviceStatus.ACTIVE, null);
        UUID id = devices.save(d).getId();
        jdbc.update("UPDATE devices SET transport = ? WHERE id = ?", transport.name(), id);
        return id;
    }

    private static TelemetryPayload sample(UUID device, String messageId, double value) {
        return new TelemetryPayload(device, Instant.parse("2026-10-17T12:00:00Z"), "TEMPERATURE", value, "CELSIUS", null, null, messageId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private CommandEnvelope command(UUID device, UUID commandId, long timestamp, Object value) {
        OutboxPayload payload = OutboxPayload.create(commandId, "LED", "SET_STATE", value, timestamp, new ObjectMapper());
        return new CommandEnvelope(commandId, device, payload.bytes(), payload.sha256(), timestamp, commandId.toString());
    }

    private UUID action(UUID device) {
        UUID id = UUID.randomUUID();
        Instant published = Instant.now().minusSeconds(2);
        jdbc.update("INSERT INTO action_history(request_id, match_id, device_id, action_type, status, parameters, timestamp, message, published_at, ack_deadline_at) "
                        + "VALUES (?, ?, ?, 'SEND_COMMAND', 'SUCCESS', '{}'::jsonb, ?, 'test', ?, ?)",
                id, UUID.randomUUID(), device, Timestamp.from(published), Timestamp.from(published), Timestamp.from(published.plusSeconds(60)));
        return id;
    }

    @Test
    void p5_090_theSimulatorTransportWorksEndToEndThroughTheSamePortsWithoutAnyBroker() throws Exception {
        assertFalse(mqttClient.isConnected(), "no broker exists in this test");
        var report = registry.reports().stream().filter(r -> r.id().equals("simulator")).findFirst().orElseThrow();
        assertEquals(java.util.Set.of(AdapterCapability.LIFECYCLE, AdapterCapability.COMMAND_EGRESS), report.capabilities());
        assertFalse(report.ingestOnly());

        UUID device = device(DeviceMode.REAL, TransportKind.SIMULATOR);
        FirmwareLikeDevice led = new FirmwareLikeDevice(device, Clock.systemUTC());
        simulator.register(led);

        // ingest: typed objects through the telemetry port; the core stores and deduplicates exactly as for any transport
        assertTrue(simulator.publishTelemetry(device, sample(device, "sim-1", 21.5)).isAccepted());
        assertTrue(simulator.publishTelemetry(device, sample(device, "sim-1", 21.5)).isAccepted(), "an identical redelivery is accepted and suppressed");
        assertEquals(1, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device));
        assertEquals(1, count("SELECT COUNT(*) FROM digital_twins WHERE device_id = ?", device));
        assertEquals(RejectionCode.DUPLICATE_CONFLICT, ((IntakeResult.Refused) simulator.publishTelemetry(device, sample(device, "sim-1", 99.0))).rejection().code());

        // command egress through the router, the way the dispatcher will reach any adapter; the answer comes back through the acknowledgement port
        UUID commandId = action(device);
        Attempt attempt = router.deliver(command(device, commandId, Instant.now().getEpochSecond(), true), Duration.ofSeconds(5));
        assertEquals(Attempt.Outcome.CONFIRMED, attempt.outcome());
        assertEquals(EvidenceKind.SIMULATOR_DELIVERED, attempt.evidence());
        assertTrue(led.ledOn(), "the in-process device applied the command");
        assertEquals("ACCEPTED", jdbc.queryForObject("SELECT ack_status FROM action_history WHERE request_id = ?", String.class, commandId));

        // a stale command is rejected by the device with the firmware's reason and recorded as REJECTED
        UUID stale = action(device);
        assertEquals(Attempt.Outcome.CONFIRMED, router.deliver(command(device, stale, Instant.now().getEpochSecond() - 60, false), Duration.ofSeconds(5)).outcome());
        assertEquals("REJECTED", jdbc.queryForObject("SELECT ack_status FROM action_history WHERE request_id = ?", String.class, stale));
        assertEquals("STALE", jdbc.queryForObject("SELECT ack_message FROM action_history WHERE request_id = ?", String.class, stale));
        assertTrue(led.ledOn(), "a stale command never reaches the actuator");

        // NOT_SENT only with proof the device was never called
        led.stop();
        Attempt stopped = router.deliver(command(device, UUID.randomUUID(), Instant.now().getEpochSecond(), true), Duration.ofSeconds(5));
        assertEquals(Attempt.Outcome.NOT_SENT, stopped.outcome());
        assertEquals("DEVICE_NOT_RUNNING", stopped.code());
        led.start();
        simulator.unregister(device);
        assertEquals("DEVICE_NOT_REGISTERED", router.deliver(command(device, UUID.randomUUID(), Instant.now().getEpochSecond(), true), Duration.ofSeconds(5)).code());
        simulator.register(led);
        simulator.stop();
        assertEquals("ADAPTER_STOPPED", router.deliver(command(device, UUID.randomUUID(), Instant.now().getEpochSecond(), true), Duration.ofSeconds(5)).code());
        simulator.start();
        assertEquals(Attempt.Outcome.CONFIRMED, router.deliver(command(device, action(device), Instant.now().getEpochSecond(), false), Duration.ofSeconds(5)).outcome());

        var counters = simulator.diagnostics();
        assertTrue(counters.get("commandsDelivered") >= 3 && counters.get("commandsRefused") >= 3 && counters.get("telemetry") >= 3, counters.toString());
        assertFalse(mqttClient.isConnected(), "and still no broker");
    }

    @Test
    void p5_092_theModeIsReportedAsTheModeAndTheTransportDecidesWhoServesTheDevice() throws Exception {
        UUID simulatedOverMqtt = device(DeviceMode.SIMULATED, TransportKind.MQTT_JSON);
        UUID realOverSimulator = device(DeviceMode.REAL, TransportKind.SIMULATOR);
        simulator.register(new FirmwareLikeDevice(simulatedOverMqtt, Clock.systemUTC()));
        simulator.register(new FirmwareLikeDevice(realOverSimulator, Clock.systemUTC()));

        // modes are reported as modes, transports as transports, and neither implies the other
        mvc.perform(get("/api/v1/devices/" + simulatedOverMqtt)).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("SIMULATED"));
        mvc.perform(get("/api/v1/devices/" + realOverSimulator)).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("REAL"));
        mvc.perform(get("/api/v1/devices/" + simulatedOverMqtt + "/transport")).andExpect(jsonPath("$.transport").value("MQTT_JSON"));
        mvc.perform(get("/api/v1/devices/" + realOverSimulator + "/transport")).andExpect(jsonPath("$.transport").value("SIMULATOR"));

        // the REAL-mode device is served by the simulator transport and refused on the MQTT one; the SIMULATED-mode device the other way round
        assertEquals(RejectionCode.TRANSPORT_MISMATCH, ((IntakeResult.Refused) simulator.publishTelemetry(simulatedOverMqtt, sample(simulatedOverMqtt, "m-1", 1))).rejection().code());
        assertEquals(0, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", simulatedOverMqtt));
        assertTrue(simulator.publishTelemetry(realOverSimulator, sample(realOverSimulator, "m-2", 2)).isAccepted());
        assertEquals(1, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", realOverSimulator));
        TransportContext mqtt = new TransportContext("mqtt-json", TransportKind.MQTT_JSON, simulatedOverMqtt, Instant.now(), "test");
        TransportContext mqttForReal = new TransportContext("mqtt-json", TransportKind.MQTT_JSON, realOverSimulator, Instant.now(), "test");
        assertTrue(mqttStyleIntake.submit(sample(simulatedOverMqtt, "m-3", 3), mqtt).isAccepted());
        assertEquals(RejectionCode.TRANSPORT_MISMATCH, ((IntakeResult.Refused) mqttStyleIntake.submit(sample(realOverSimulator, "m-4", 4), mqttForReal)).rejection().code());
        assertEquals(1, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", realOverSimulator));
        // a device that is not registered or not running in-process cannot publish through the simulator transport
        UUID unregistered = device(DeviceMode.SIMULATED, TransportKind.SIMULATOR);
        assertEquals(RejectionCode.FORBIDDEN_DEVICE, ((IntakeResult.Refused) simulator.publishTelemetry(unregistered, sample(unregistered, "m-5", 5))).rejection().code());

        // assignment API: now allowed because the adapter is registered; refused while a command is being delivered (covered with the MQTT case)
        UUID movable = device(DeviceMode.SIMULATED, TransportKind.MQTT_JSON);
        mvc.perform(put("/api/v1/devices/" + movable + "/transport").contentType(MediaType.APPLICATION_JSON).content("{\"transport\":\"SIMULATOR\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.transport").value("SIMULATOR"));
        mvc.perform(get("/api/v1/devices/" + movable)).andExpect(jsonPath("$.mode").value("SIMULATED"));
    }

    @Test
    void theSimulatorTransportIsFailClosedForCommandAcceptanceBecauseTheDispatcherIsNotRoutedYet() {
        UUID device = device(DeviceMode.REAL, TransportKind.SIMULATOR);
        simulator.register(new FirmwareLikeDevice(device, Clock.systemUTC()));
        Eligibility production = eligibility.eligible(device, Instant.now());
        assertFalse(production.eligible());
        assertEquals(SimulatorTransportEligibility.DELIVERY_NOT_ROUTED, production.code());
        // the running-device logic exists and works once delivery is routed
        SimulatorTransportEligibility routed = new SimulatorTransportEligibility(simulator, true);
        assertTrue(routed.eligible(device, Instant.now()).eligible());
        simulator.unregister(device);
        assertEquals(SimulatorTransportEligibility.DEVICE_NOT_RUNNING, routed.eligible(device, Instant.now()).code());
        FirmwareLikeDevice stopped = new FirmwareLikeDevice(device, Clock.systemUTC());
        stopped.stop();
        simulator.register(stopped);
        assertFalse(routed.eligible(device, Instant.now()).eligible());
        simulator.unregister(device);
    }

    @Test
    void theFirmwareLikeReferenceDeviceAppliesTheFirmwareRulesWithAnInjectedClock() {
        long now = 1_767_225_600L;
        UUID id = UUID.randomUUID();
        FirmwareLikeDevice led = new FirmwareLikeDevice(id, Clock.fixed(Instant.ofEpochSecond(now), java.time.ZoneOffset.UTC));
        for (long offset : new long[]{-30, -29, 0, 29, 30}) {
            var ack = led.onCommand(command(id, UUID.randomUUID(), now + offset, true)).orElseThrow();
            assertEquals("ACCEPTED", ack.status().name(), "offset " + offset);
        }
        for (long offset : new long[]{-31, 31, -300, 300}) {
            var ack = led.onCommand(command(id, UUID.randomUUID(), now + offset, true)).orElseThrow();
            assertEquals(("REJECTED/STALE"), ack.status().name() + "/" + ack.reason(), "offset " + offset);
        }
        UUID repeated = UUID.randomUUID();
        led.onCommand(command(id, repeated, now, true));
        assertEquals("ACCEPTED", led.onCommand(command(id, repeated, now - 1000, false)).orElseThrow().status().name(), "a repeated commandId is answered with the first outcome");
        assertTrue(led.ledOn(), "and applied once");
        assertEquals("MALFORMED", led.onCommand(new CommandEnvelope(UUID.randomUUID(), id,
                ("{\"commandId\":\"" + UUID.randomUUID() + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":\"on\",\"timestamp\":" + now + "}").getBytes(), "h", now, "c")).orElseThrow().reason());
        assertEquals("UNSUPPORTED", led.onCommand(new CommandEnvelope(UUID.randomUUID(), id,
                ("{\"commandId\":\"" + UUID.randomUUID() + "\",\"capability\":\"FAN\",\"command\":\"SET_STATE\",\"value\":true,\"timestamp\":" + now + "}").getBytes(), "h", now, "c")).orElseThrow().reason());
        assertTrue(led.onCommand(new CommandEnvelope(UUID.randomUUID(), id, "not json".getBytes(), "h", now, "c")).isEmpty(), "nothing to correlate, no acknowledgement");
        assertTrue(led.onCommand(new CommandEnvelope(UUID.randomUUID(), id,
                ("{\"commandId\":\"" + UUID.randomUUID() + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true,\"timestamp\":" + now + ".5}").getBytes(), "h", now, "c")).orElseThrow().reason().equals("MALFORMED"));
    }

    @SuppressWarnings("unused")
    private static final Class<?>[] TYPES = {SimulatedDevice.class};
}
