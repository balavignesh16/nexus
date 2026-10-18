package com.nexus.adapter;

import com.nexus.adapter.core.AdapterRegistry;
import com.nexus.adapter.core.MqttTransportEligibility;
import com.nexus.adapter.core.TransportAssignments;
import com.nexus.adapter.core.TransportEligibilityRouter;
import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.AcknowledgementIntake;
import com.nexus.adapter.spi.ConnectivityIntake;
import com.nexus.adapter.spi.ConnectivityObservation;
import com.nexus.adapter.spi.Eligibility;
import com.nexus.adapter.spi.IntakeResult;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryIntake;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.adapter.spi.TransportContext;
import com.nexus.adapter.spi.TransportEligibility;
import com.nexus.adapter.spi.TransportKind;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The adapter core wired into the real application on a real PostgreSQL (ledger P5-035, P5-036, P5-123, P5-142): the intake ports reach
 * the unchanged core services, the server-side assignment alone decides the transport, and the MQTT acceptance rule keeps its wording.
 * One fake ingest-only adapter for HTTP_WEBHOOK is registered so the registry, the diagnostics endpoint and the assignment API have something to serve.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(AdapterWiringIntegrationTest.Adapters.class)
class AdapterWiringIntegrationTest {

    @TestConfiguration
    static class Adapters {
        @Bean
        FakeAdapters.IngestOnly httpWebhookFake() {
            return new FakeAdapters.IngestOnly("http-webhook", TransportKind.HTTP_WEBHOOK);
        }
    }

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
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdapterRegistry registry;
    @Autowired TransportAssignments assignments;
    @Autowired TransportEligibility eligibility;
    @Autowired TelemetryIntake telemetryIntake;
    @Autowired AcknowledgementIntake ackIntake;
    @Autowired ConnectivityIntake connectivityIntake;
    @Autowired DeviceConnectivityRegistry connectivity;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;

    private UUID activeDevice() {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Adapter", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "test", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "test", now, now));
        Device d = new Device(UUID.randomUUID(), space, "M", DeviceType.CUSTOM, "NEXUS", "TEST", "P52-" + UUID.randomUUID(), null, DeviceMode.SIMULATED);
        d.update("M", DeviceStatus.ACTIVE, null);
        return devices.save(d).getId();
    }

    private static TransportContext mqtt(UUID device) {
        return new TransportContext("mqtt-json", TransportKind.MQTT_JSON, device, Instant.now(), "test");
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String transportOf(UUID device) {
        return jdbc.queryForObject("SELECT transport FROM devices WHERE id = ?", String.class, device);
    }

    @Test
    void theAdapterCoreIsWiredAndTheRouterIsTheEligibilityTheExecutorUses() {
        assertInstanceOf(TransportEligibilityRouter.class, eligibility, "the primary TransportEligibility is the router");
        assertEquals(1, registry.reports().size());
        assertEquals("http-webhook", registry.reports().get(0).id());
        assertTrue(registry.reports().get(0).ingestOnly());
    }

    @Test
    void p5_035_telemetryReachesTheUnchangedServiceAndTheCoreDeduplicatesByMessageId() {
        UUID device = activeDevice();
        TelemetryPayload sample = new TelemetryPayload(device, Instant.parse("2026-10-17T12:00:00Z"), "TEMPERATURE", 21.5, "CELSIUS", null, null, "adapter-dedupe-1");
        assertTrue(telemetryIntake.submit(sample, mqtt(device)).isAccepted());
        assertTrue(telemetryIntake.submit(sample, mqtt(device)).isAccepted(), "an identical redelivery is accepted and suppressed by the core inbox");
        assertEquals(1, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device));
        assertEquals(1, count("SELECT COUNT(*) FROM inbound_message_dedupe WHERE device_id = ? AND message_id = 'adapter-dedupe-1'", device));
        assertEquals(1, count("SELECT COUNT(*) FROM digital_twins WHERE device_id = ?", device));
        var conflicting = new TelemetryPayload(device, Instant.parse("2026-10-17T12:00:00Z"), "TEMPERATURE", 99.0, "CELSIUS", null, null, "adapter-dedupe-1");
        var refused = assertInstanceOf(IntakeResult.Refused.class, telemetryIntake.submit(conflicting, mqtt(device)));
        assertEquals(RejectionCode.DUPLICATE_CONFLICT, refused.rejection().code(), "the core's conflict semantics are reported as a fixed code");
        assertEquals(1, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device), "nothing was stored for the conflict");
    }

    @Test
    void p5_035_connectivityAndAcknowledgementReachTheSameApplicationStepsAsTheMqttPath() {
        UUID device = activeDevice();
        assertTrue(connectivityIntake.submit(new ConnectivityObservation(device, ConnectivityObservation.State.ONLINE), mqtt(device)).isAccepted());
        assertEquals(DeviceConnectivityStatus.ONLINE, connectivity.get(device).orElseThrow().status());
        assertTrue(connectivityIntake.submit(new ConnectivityObservation(device, ConnectivityObservation.State.OFFLINE), mqtt(device)).isAccepted());
        assertEquals(DeviceConnectivityStatus.OFFLINE, connectivity.get(device).orElseThrow().status());

        UUID command = UUID.randomUUID();
        Instant published = Instant.now().minusSeconds(5);
        jdbc.update("INSERT INTO action_history(request_id, match_id, device_id, action_type, status, parameters, timestamp, message, published_at, ack_deadline_at) "
                        + "VALUES (?, ?, ?, 'SEND_COMMAND', 'SUCCESS', '{}'::jsonb, ?, 'test', ?, ?)",
                command, UUID.randomUUID(), device, Timestamp.from(published), Timestamp.from(published), Timestamp.from(published.plusSeconds(60)));
        assertTrue(ackIntake.submit(new AckPayload(command, AckPayload.Status.REJECTED, Instant.now(), "STALE"), mqtt(device)).isAccepted());
        assertEquals("REJECTED", jdbc.queryForObject("SELECT ack_status FROM action_history WHERE request_id = ?", String.class, command));
        assertTrue(ackIntake.submit(new AckPayload(command, AckPayload.Status.ACCEPTED, Instant.now(), null), mqtt(device)).isAccepted(),
                "a second acknowledgement is handled by the core's first-ACK-wins rule, not by the port");
        assertEquals("REJECTED", jdbc.queryForObject("SELECT ack_status FROM action_history WHERE request_id = ?", String.class, command));
    }

    @Test
    void p5_036_theServerSideAssignmentAloneDecidesWhichTransportIsAccepted() {
        UUID device = activeDevice();
        TelemetryPayload sample = new TelemetryPayload(device, Instant.parse("2026-10-17T12:00:00Z"), "TEMPERATURE", 20.0, "CELSIUS", null, null, "assignment-1");
        TransportContext http = new TransportContext("http-webhook", TransportKind.HTTP_WEBHOOK, device, Instant.now(), "test");
        assertEquals(RejectionCode.TRANSPORT_MISMATCH, assertInstanceOf(IntakeResult.Refused.class, telemetryIntake.submit(sample, http)).rejection().code());
        assertEquals(0, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device), "nothing reached the core");
        jdbc.update("UPDATE devices SET transport = 'HTTP_WEBHOOK' WHERE id = ?", device);   // the assignment changes; the message does not
        assertEquals(RejectionCode.TRANSPORT_MISMATCH, assertInstanceOf(IntakeResult.Refused.class, telemetryIntake.submit(sample, mqtt(device))).rejection().code());
        assertTrue(telemetryIntake.submit(sample, http).isAccepted());
        assertEquals(1, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device));
    }

    @Test
    void p5_142_theMqttRuleIsUnchangedBehindThePortAndOtherTransportsFailClosed() {
        UUID device = activeDevice();
        Eligibility offline = eligibility.eligible(device, Instant.now());
        assertFalse(offline.eligible());
        assertEquals(MqttTransportEligibility.OFFLINE_MESSAGE, offline.message(), "no connectivity observation yet: the former wording");
        connectivityIntake.submit(new ConnectivityObservation(device, ConnectivityObservation.State.ONLINE), mqtt(device));
        Eligibility disconnected = eligibility.eligible(device, Instant.now());
        assertFalse(disconnected.eligible());
        assertEquals(MqttTransportEligibility.BROKER_DISCONNECTED_MESSAGE, disconnected.message(), "online in the registry, but the test application has no broker connection");

        jdbc.update("UPDATE devices SET transport = 'HTTP_WEBHOOK' WHERE id = ?", device);
        Eligibility http = eligibility.eligible(device, Instant.now());
        assertFalse(http.eligible());
        assertEquals(TransportEligibilityRouter.NO_ADAPTER, http.code(), "no eligibility rule exists for HTTP yet, so it is refused rather than allowed");
        assertEquals("DEVICE_UNKNOWN", eligibility.eligible(UUID.randomUUID(), Instant.now()).code());
    }

    @Test
    void p5_123_anUnknownTransportIsRefusedWithBadRequestAndTheAssignmentIsKept() throws Exception {
        UUID device = activeDevice();
        mvc.perform(get("/api/v1/devices/" + device + "/transport")).andExpect(status().isOk()).andExpect(jsonPath("$.transport").value("MQTT_JSON"));
        for (String bad : new String[]{"BLE", "http_webhook", "", "MQTT_JSON "}) {
            mvc.perform(put("/api/v1/devices/" + device + "/transport").contentType(MediaType.APPLICATION_JSON).content("{\"transport\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/v1/devices/" + device + "/transport").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        assertEquals("MQTT_JSON", transportOf(device), "the device keeps its previous assignment");
        mvc.perform(put("/api/v1/devices/" + UUID.randomUUID() + "/transport").contentType(MediaType.APPLICATION_JSON).content("{\"transport\":\"MQTT_JSON\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/devices/" + UUID.randomUUID() + "/transport")).andExpect(status().isNotFound());
    }

    @Test
    void theAssignmentNeedsARegisteredAdapterAndIsRefusedWhileACommandIsStillBeingDelivered() throws Exception {
        UUID device = activeDevice();
        mvc.perform(put("/api/v1/devices/" + device + "/transport").contentType(MediaType.APPLICATION_JSON).content("{\"transport\":\"SIMULATOR\"}"))
                .andExpect(status().isConflict());
        assertEquals("MQTT_JSON", transportOf(device), "no SIMULATOR adapter is registered, so the device is not left unserved");

        mvc.perform(put("/api/v1/devices/" + device + "/transport").contentType(MediaType.APPLICATION_JSON).content("{\"transport\":\"MQTT_JSON\"}"))
                .andExpect(status().isOk());

        Instant now = Instant.now();
        UUID command = UUID.randomUUID();
        jdbc.update("INSERT INTO action_history(request_id, match_id, device_id, action_type, status, parameters, timestamp, message) VALUES (?, ?, ?, 'SEND_COMMAND', 'ACCEPTED_FOR_DISPATCH', '{}'::jsonb, ?, 'test')",
                command, UUID.randomUUID(), device, Timestamp.from(now));
        String text = "{\"commandId\":\"" + command + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true,\"timestamp\":" + now.getEpochSecond() + "}";
        jdbc.update("INSERT INTO command_outbox(command_id, device_id, topic, payload_text, payload_sha256, payload_epoch_seconds, state, attempt_count, max_attempts, next_attempt_at, expires_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', 0, 3, ?, ?, ?, ?)", command, device, "nexus/devices/" + device + "/commands", text,
                com.nexus.action.outbox.OutboxPayload.sha256Hex(text), now.getEpochSecond(), Timestamp.from(now), Timestamp.from(now.plusSeconds(15)), Timestamp.from(now), Timestamp.from(now));
        mvc.perform(put("/api/v1/devices/" + device + "/transport").contentType(MediaType.APPLICATION_JSON).content("{\"transport\":\"HTTP_WEBHOOK\"}"))
                .andExpect(status().isConflict());
        assertEquals("MQTT_JSON", transportOf(device));
        jdbc.update("UPDATE command_outbox SET state = 'DEAD_LETTER', next_attempt_at = NULL, terminal_at = now(), terminal_reason = 'EXPIRED' WHERE command_id = ?", command);
        mvc.perform(put("/api/v1/devices/" + device + "/transport").contentType(MediaType.APPLICATION_JSON).content("{\"transport\":\"HTTP_WEBHOOK\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.transport").value("HTTP_WEBHOOK"));
        assertEquals("HTTP_WEBHOOK", transportOf(device));
        assertEquals(TransportAssignments.Result.UNCHANGED, assignments.assign(device, TransportKind.HTTP_WEBHOOK));
    }

    @Test
    void theDiagnosticsEndpointListsAdaptersWithoutCredentialMaterial() throws Exception {
        mvc.perform(get("/api/v1/adapters")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("http-webhook")).andExpect(jsonPath("$[0].ingestOnly").value(true))
                .andExpect(jsonPath("$[0].transport").value("HTTP_WEBHOOK")).andExpect(jsonPath("$[0].capabilities[0]").value("TELEMETRY_DECODER"));
    }
}
