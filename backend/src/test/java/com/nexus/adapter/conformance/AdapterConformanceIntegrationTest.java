package com.nexus.adapter.conformance;

import com.nexus.adapter.conformance.ConformanceReport.Outcome;
import com.nexus.adapter.conformance.Subject.IngestProbe;
import com.nexus.adapter.core.AdapterRegistry;
import com.nexus.adapter.core.HttpTransportEligibility;
import com.nexus.adapter.core.SimulatorTransportEligibility;
import com.nexus.adapter.credentials.CredentialAdmin;
import com.nexus.adapter.http.HttpJsonDecoder;
import com.nexus.adapter.http.HttpWebhookAdapter;
import com.nexus.adapter.http.KeyFormat;
import com.nexus.adapter.http.KeyHasher;
import com.nexus.adapter.mqtt.MqttJsonAdapter;
import com.nexus.adapter.simulator.FirmwareLikeDevice;
import com.nexus.adapter.simulator.SimulatorAdapter;
import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.Attempt;
import com.nexus.adapter.spi.CommandEnvelope;
import com.nexus.adapter.spi.Decoded;
import com.nexus.adapter.spi.EvidenceKind;
import com.nexus.adapter.spi.IntakeResult;
import com.nexus.adapter.spi.RawMessage;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryIntake;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.adapter.spi.TransportEligibility;
import com.nexus.adapter.spi.TransportKind;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.TelemetryService;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The conformance kit on the RUNNING application (ledger P5-104 to P5-106, P5-082, P5-108 live, P5-109, P5-122): real PostgreSQL, a real
 * Mosquitto, the application's own listener, controllers, bridges and core, and all three adapters enabled in one context. The kit is the very
 * code the UNIT tests run; only the subjects differ (they reach the live beans), and the receive-path scenarios are only meaningful here.
 *
 * <p>The "core" the ingest scenarios observe is the unchanged telemetry service (a Mockito spy that calls the real method) and the real
 * database rows, never an adapter-side counter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdapterConformanceIntegrationTest {
    static final String PEPPER = "conformance-live-pepper-0123456789abcdef";
    static final String TELEMETRY = "/api/v1/device-adapter/devices/%s/telemetry";
    static final String ACKS = "/api/v1/device-adapter/devices/%s/acknowledgements";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @Container
    static GenericContainer<?> mosquitto = new GenericContainer<>("eclipse-mosquitto:2.0")
            .withExposedPorts(1883)
            .withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto-test/mosquitto.conf"), "/mosquitto/config/mosquitto.conf")
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("mqtt.broker.url", () -> "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883));
        registry.add("mqtt.client.id", () -> "p57-app-" + UUID.randomUUID());
        registry.add("nexus.adapter.http.enabled", () -> "true");
        registry.add("nexus.adapter.simulator.enabled", () -> "true");
        registry.add("NEXUS_DEVICE_KEY_PEPPER", () -> PEPPER);
        registry.add("server.address", () -> "127.0.0.1");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdapterRegistry registry;
    @Autowired MqttJsonAdapter mqttAdapter;
    @Autowired HttpWebhookAdapter httpAdapter;
    @Autowired SimulatorAdapter simulator;
    @Autowired TelemetryIntake telemetryIntake;
    @Autowired TransportEligibility eligibility;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @SpyBean TelemetryService telemetryService;
    @SpyBean DeviceConnectivityRegistry connectivity;

    private final List<MqttClient> clients = new ArrayList<>();

    @AfterEach
    void restore() {
        httpAdapter.start();
        simulator.start();
        mqttAdapter.start();
        for (MqttClient c : clients) {
            try { if (c.isConnected()) c.disconnectForcibly(0, 0, false); c.close(true); } catch (MqttException ignored) { }
        }
        clients.clear();
    }

    // ------------------------------------------------------------------------------------------------ fixtures

    private UUID device(TransportKind transport) {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Conf", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "test", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "test", now, now));
        Device d = new Device(UUID.randomUUID(), space, "M", DeviceType.CUSTOM, "NEXUS", "TEST", "P57-" + UUID.randomUUID(), null, DeviceMode.SIMULATED);
        d.update("M", DeviceStatus.ACTIVE, null);
        UUID id = devices.save(d).getId();
        jdbc.update("UPDATE devices SET transport = ? WHERE id = ?", transport.name(), id);
        return id;
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long stored(UUID device) {
        return count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    private CredentialAdmin admin() {
        return new CredentialAdmin(() -> DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()),
                new KeyHasher(PEPPER.getBytes(StandardCharsets.UTF_8)), new SecureRandom(), "tester");
    }

    private MqttClient connect() throws MqttException {
        MqttClient client = new MqttClient("tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883), "p57-" + UUID.randomUUID(), new MemoryPersistence());
        clients.add(client);
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        client.connect(options);
        return client;
    }

    /** An ACK needs a command to answer: a SEND_COMMAND row published a moment ago, exactly what the dispatcher leaves behind. */
    private UUID pendingCommand(UUID device) {
        UUID id = UUID.randomUUID();
        Instant published = Instant.now().minusSeconds(2);
        jdbc.update("INSERT INTO action_history(request_id, match_id, device_id, action_type, status, parameters, timestamp, message, published_at, ack_deadline_at) "
                        + "VALUES (?, ?, ?, 'SEND_COMMAND', 'SUCCESS', '{}'::jsonb, ?, 'test', ?, ?)",
                id, UUID.randomUUID(), device, Timestamp.from(published), Timestamp.from(published), Timestamp.from(published.plusSeconds(60)));
        return id;
    }

    private boolean acknowledged(UUID commandId) {
        return jdbc.queryForObject("SELECT ack_status FROM action_history WHERE request_id = ?", String.class, commandId) != null;
    }

    private static byte[] withCommand(byte[] payload, UUID commandId) {
        return new String(payload, StandardCharsets.ISO_8859_1).replace(SharedCases.COMMAND.toString(), commandId.toString()).getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Invocations of the spied core entry points since a baseline, for one device. */
    private int telemetryCallsSince(int baseline, UUID device) {
        int calls = 0;
        List<Invocation> all = new ArrayList<>(Mockito.mockingDetails(telemetryService).getInvocations());
        for (int i = baseline; i < all.size(); i++) {
            Invocation invocation = all.get(i);
            if (invocation.getMethod().getName().equals("processTelemetry") && ((TelemetryRequest) invocation.getArgument(0)).deviceId().equals(device)) calls++;
        }
        return calls;
    }

    private int telemetryBaseline() {
        return Mockito.mockingDetails(telemetryService).getInvocations().size();
    }

    private int statusCallsSince(int baseline, UUID device) {
        int calls = 0;
        List<Invocation> all = new ArrayList<>(Mockito.mockingDetails(connectivity).getInvocations());
        for (int i = baseline; i < all.size(); i++) {
            Invocation invocation = all.get(i);
            if (invocation.getMethod().getName().equals("applyObservedStatus") && invocation.getArgument(0).equals(device)) calls++;
        }
        return calls;
    }

    private int statusBaseline() {
        return Mockito.mockingDetails(connectivity).getInvocations().size();
    }

    private static String sample(UUID device, String messageId) {
        return "{\"deviceId\":\"" + device + "\",\"timestamp\":\"2026-10-17T12:00:00Z\",\"sensorType\":\"TEMPERATURE\",\"value\":21.5,\"unit\":\"CELSIUS\",\"messageId\":\"" + messageId + "\"}";
    }

    // ------------------------------------------------------------------------------------------------ live subjects

    /** MQTT: bytes are published to the real broker, the application's own listener receives them, and the spied core and the rows are observed. */
    private final class MqttIngest implements IngestProbe {
        private final MqttClient publisher;
        private final MqttClient sentinelPublisher;
        private UUID last;
        private UUID lastCommand;
        private int telemetryBaseline, statusBaseline;

        MqttIngest() throws MqttException {
            publisher = connect();
            sentinelPublisher = publisher;
        }

        @Override public UUID newDevice() { return device(TransportKind.MQTT_JSON); }

        @Override
        public void send(Kind kind, byte[] payload, UUID device) {
            try {
                last = device;
                byte[] body = payload;
                if (kind == Kind.ACKNOWLEDGEMENT) {
                    lastCommand = pendingCommand(device);
                    body = withCommand(payload, lastCommand);
                }
                String suffix = switch (kind) {
                    case TELEMETRY -> "/telemetry";
                    case ACKNOWLEDGEMENT -> "/commands/ack";
                    case STATUS -> "/status";
                };
                MqttMessage message = new MqttMessage(body);
                message.setQos(1);
                publisher.publish("nexus/devices/" + device + suffix, message);
                barrier();
            } catch (MqttException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The single listener thread handles the single publisher's QoS 1 stream in order: when the sentinel is processed, everything before it is. */
        private void barrier() throws MqttException, InterruptedException {
            UUID sentinel = device(TransportKind.MQTT_JSON);
            int before = telemetryBaseline();
            MqttMessage message = new MqttMessage(sample(sentinel, "sentinel-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            sentinelPublisher.publish("nexus/devices/" + sentinel + "/telemetry", message);
            await(() -> telemetryCallsSince(before, sentinel) >= 1, "the sentinel to be processed");
        }

        @Override
        public int coreCalls(Kind kind) {
            return switch (kind) {
                case TELEMETRY -> telemetryCallsSince(telemetryBaseline, last);
                case ACKNOWLEDGEMENT -> lastCommand != null && acknowledged(lastCommand) ? 1 : 0;
                case STATUS -> statusCallsSince(statusBaseline, last);
            };
        }

        @Override
        public void reset() {
            telemetryBaseline = telemetryBaseline();
            statusBaseline = statusBaseline();
            lastCommand = null;
        }

        @Override public long storedTelemetry(UUID device) { return stored(device); }
    }

    private Subject mqttSubject() throws Exception {
        Subject unit = UnitSubjects.mqtt();
        MqttClient subscriber = connect();
        List<byte[]> received = new CopyOnWriteArrayList<>();
        UUID commandDevice = UUID.fromString("5d0c9a30-1b2e-4c3d-9e4f-0a1b2c3d4e5f");
        subscriber.subscribe("nexus/devices/" + commandDevice + "/commands", 1, (topic, message) -> received.add(message.getPayload()));
        Subject.EgressProbe egress = new Subject.EgressProbe() {
            @Override
            public Optional<Delivery> deliver(Mode mode, UUID device, byte[] payload) {
                if (mode != Mode.CONFIRMED) return Optional.empty();
                received.clear();
                Attempt attempt = mqttAdapter.deliver(new CommandEnvelope(UUID.randomUUID(), device, payload, "hash", 1767225600L, "c"), Duration.ofSeconds(10));
                try {
                    await(() -> !received.isEmpty(), "the broker to deliver the command to the subscriber");
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return Optional.of(new Delivery(attempt, 1, received.get(0), null));
            }

            @Override
            public String cannotForce(Mode mode) {
                return "a live broker cannot be made to refuse or time out on demand; the NOT_SENT / UNCERTAIN classification is forced against the same adapter in the UNIT run";
            }

            @Override public EvidenceKind expectedEvidence() { return EvidenceKind.BROKER_CONFIRMED; }
        };
        return new UnitSubjects.SubjectImpl("mqtt-json", Subject.Level.LIVE, mqttAdapter, unit.wire(), Optional.of(new MqttIngest()), Optional.of(egress), Optional.empty(), Map.of(),
                UnitSubjects.MQTT_REGISTERED);
    }

    /** HTTP: requests go through the real controller, filters, authentication and bridges. */
    private final class HttpIngest implements IngestProbe {
        private final Map<UUID, String> keys = new HashMap<>();
        private UUID last;
        private UUID lastCommand;
        private int telemetryBaseline;

        @Override
        public UUID newDevice() {
            UUID id = device(TransportKind.HTTP_WEBHOOK);
            keys.put(id, admin().create(id).key());
            return id;
        }

        @Override public boolean supports(Kind kind) { return kind != Kind.STATUS; }

        @Override
        public void send(Kind kind, byte[] payload, UUID device) {
            try {
                last = device;
                byte[] body = payload;
                String path = TELEMETRY;
                if (kind == Kind.ACKNOWLEDGEMENT) {
                    lastCommand = pendingCommand(device);
                    body = withCommand(payload, lastCommand);
                    path = ACKS;
                }
                mvc.perform(post(path.formatted(device)).header("Authorization", "Bearer " + keys.get(device)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public int coreCalls(Kind kind) {
            return kind == Kind.ACKNOWLEDGEMENT ? (lastCommand != null && acknowledged(lastCommand) ? 1 : 0) : telemetryCallsSince(telemetryBaseline, last);
        }

        @Override
        public void reset() {
            telemetryBaseline = telemetryBaseline();
            lastCommand = null;
        }

        @Override public long storedTelemetry(UUID device) { return stored(device); }
    }

    private Subject httpSubject() {
        Subject unit = UnitSubjects.http();
        CredentialAdmin admin = admin();
        UUID authDevice = device(TransportKind.HTTP_WEBHOOK);
        CredentialAdmin.Issued valid = admin.create(authDevice);
        KeyFormat.Parsed parsed = KeyFormat.parse(valid.key()).orElseThrow();
        UUID revokedDevice = device(TransportKind.HTTP_WEBHOOK);
        CredentialAdmin.Issued toRevoke = admin.create(revokedDevice);
        admin.revoke(toRevoke.lookupId());
        UUID inactiveDevice = device(TransportKind.HTTP_WEBHOOK);
        String inactiveKey = admin.create(inactiveDevice).key();
        jdbc.update("UPDATE devices SET status = 'MAINTENANCE' WHERE id = ?", inactiveDevice);
        Subject.AuthProbe auth = new Subject.AuthProbe() {
            @Override public String validCredential() { return valid.key(); }
            @Override public UUID deviceOfValidCredential() { return authDevice; }
            @Override public String wrongSecret() { return KeyFormat.format(parsed.lookupId(), "A".repeat(KeyFormat.SECRET_LENGTH)); }
            @Override public String unknownCredential() { return KeyFormat.format("0".repeat(KeyFormat.LOOKUP_LENGTH), parsed.secret()); }
            @Override public String revokedCredential() { return toRevoke.key(); }
            @Override public String inactiveDeviceCredential() { return inactiveKey; }

            @Override
            public RawMessage message(String credential) {
                Map<String, String> headers = new HashMap<>();
                if (credential != null) headers.put(HttpWebhookAdapter.AUTHORIZATION, "Bearer " + credential);
                return new RawMessage(new byte[0], null, headers);
            }
        };
        return new UnitSubjects.SubjectImpl("http-webhook", Subject.Level.LIVE, httpAdapter, unit.wire(), Optional.of(new HttpIngest()), Optional.empty(), Optional.of(auth),
                Map.of("ingest.status-reaches-the-core-and-invalid-status-does-not",
                        "an HTTP status post is recorded as a device claim and does not drive the connectivity registry (adapter-contract.md section 10.8)"));
    }

    /** The simulator hands typed objects to the core: the shared JSON is translated to typed values by the strict decoder before it is handed over. */
    private final class SimulatorIngest implements IngestProbe {
        private final HttpJsonDecoder translate = new HttpJsonDecoder();
        private UUID last;
        private UUID lastCommand;
        private int telemetryBaseline;

        @Override
        public UUID newDevice() {
            UUID id = device(TransportKind.SIMULATOR);
            simulator.register(new FirmwareLikeDevice(id, Clock.systemUTC()));
            return id;
        }

        @Override public boolean supports(Kind kind) { return kind != Kind.STATUS; }

        @Override
        public void send(Kind kind, byte[] payload, UUID device) {
            last = device;
            if (kind == Kind.TELEMETRY) {
                if (translate.decodeTelemetry(payload, null) instanceof Decoded.Accepted<TelemetryPayload> accepted) simulator.publishTelemetry(device, accepted.value());
            } else {
                lastCommand = pendingCommand(device);
                if (translate.decodeAcknowledgement(withCommand(payload, lastCommand), null) instanceof Decoded.Accepted<AckPayload> accepted) simulator.publishAcknowledgement(device, accepted.value());
            }
        }

        @Override
        public int coreCalls(Kind kind) {
            return kind == Kind.ACKNOWLEDGEMENT ? (lastCommand != null && acknowledged(lastCommand) ? 1 : 0) : telemetryCallsSince(telemetryBaseline, last);
        }

        @Override
        public void reset() {
            telemetryBaseline = telemetryBaseline();
            lastCommand = null;
        }

        @Override public long storedTelemetry(UUID device) { return stored(device); }
    }

    private Subject simulatorSubject() {
        Subject.EgressProbe egress = UnitSubjects.simulatorEgress(simulator);   // bound to the LIVE adapter, so its counters are the live counters
        Map<String, String> notApplicable = Map.of(
                "ingest.invalid-telemetry-never-reaches-the-core", "the simulator hands typed objects to the core; a malformed document cannot be expressed (typed refusals are asserted by simulatorNegativePathsNeverReachTheCore)",
                "ingest.invalid-acknowledgement-never-reaches-the-core", "typed objects only (see the same test)",
                "ingest.status-reaches-the-core-and-invalid-status-does-not", "the simulator has no status input; liveness is the in-process device's own state");
        return new UnitSubjects.SubjectImpl("simulator", Subject.Level.LIVE, simulator, Optional.empty(), Optional.of(new SimulatorIngest()), Optional.of(egress), Optional.empty(), notApplicable);
    }

    /** The test-only fourth adapter against the live core, standing in the free SIMULATOR slot of its own (non-production) registry. */
    private Subject serialSubject() {
        SerialLikeAdapter adapter = new SerialLikeAdapter(telemetryIntake, Clock.systemUTC());
        adapter.start();
        Subject unit = UnitSubjects.serial();
        IngestProbe probe = new IngestProbe() {
            private UUID last;
            private int baseline;

            @Override
            public UUID newDevice() {
                UUID id = device(TransportKind.SIMULATOR);   // the stand-in slot: the core accepts it as the transport the server assigned
                adapter.bind("port-" + id, id);
                return id;
            }

            @Override public boolean supports(Kind kind) { return kind == Kind.TELEMETRY; }

            @Override
            public void send(Kind kind, byte[] payload, UUID device) {
                last = device;
                byte[] framed = java.util.Arrays.copyOf(payload, payload.length + 1);
                framed[payload.length] = '\n';
                adapter.onFrame("port-" + device, framed);
            }

            @Override public int coreCalls(Kind kind) { return telemetryCallsSince(baseline, last); }
            @Override public void reset() { baseline = telemetryBaseline(); }
            @Override public long storedTelemetry(UUID device) { return stored(device); }
        };
        return new UnitSubjects.SubjectImpl("serial-test", Subject.Level.LIVE, adapter, unit.wire(), Optional.of(probe), Optional.empty(), Optional.empty(), Map.of());
    }

    private static void assertConforms(ConformanceReport report) {
        System.out.println(report.text());
        assertEquals(List.of(), report.failures(), report.text());
        assertTrue(report.conforms(), report.text());
    }

    // ------------------------------------------------------------------------------------------------ the kit on the live adapters

    @Test
    void p5_104_mqttJsonPassesTheKitOnARealBrokerAndDatabase() throws Exception {
        ConformanceReport report = ConformanceKit.run(mqttSubject());
        assertConforms(report);
        assertEquals(Outcome.PASS, report.scenario("ingest.valid-telemetry-reaches-the-core-exactly-once").outcome());
        assertEquals(Outcome.PASS, report.scenario("ingest.invalid-telemetry-never-reaches-the-core").outcome());
        assertEquals(Outcome.PASS, report.scenario("ingest.valid-acknowledgement-reaches-the-core-exactly-once").outcome());
        assertEquals(Outcome.PASS, report.scenario("ingest.invalid-acknowledgement-never-reaches-the-core").outcome());
        assertEquals(Outcome.PASS, report.scenario("ingest.status-reaches-the-core-and-invalid-status-does-not").outcome(), "plain text, trimmed, case-sensitive");
        assertEquals(Outcome.PASS, report.scenario("ingest.an-identical-redelivery-is-stored-once").outcome());
        assertEquals(Outcome.PASS, report.scenario("egress.confirmed-states-its-evidence-and-sends-the-stored-bytes").outcome());
        assertEquals(Outcome.NOT_APPLICABLE, report.scenario("egress.doubt-is-uncertain").outcome(), "forced in the UNIT run, never claimed here");
        assertEquals(Outcome.NOT_SUPPORTED, report.scenario("authenticator.credential-shapes").outcome());
        assertTrue(report.conformantCapabilities().containsAll(java.util.Set.of("LIFECYCLE", "TELEMETRY_DECODER", "ACKNOWLEDGEMENT_DECODER", "CONNECTIVITY_SOURCE", "COMMAND_EGRESS")));
    }

    @Test
    void p5_105_p5_082_httpPassesTheKitForItsIngestCapabilitiesAndCommandEgressIsNotSupported() throws Exception {
        ConformanceReport report = ConformanceKit.run(httpSubject());
        assertConforms(report);
        assertEquals(java.util.Set.of(AdapterCapability.LIFECYCLE, AdapterCapability.TELEMETRY_DECODER, AdapterCapability.ACKNOWLEDGEMENT_DECODER, AdapterCapability.DEVICE_AUTHENTICATOR), report.declared());
        assertEquals(Outcome.PASS, report.scenario("authenticator.credential-shapes").outcome());
        assertEquals(Outcome.PASS, report.scenario("authenticator.an-inactive-device-is-forbidden").outcome());
        assertEquals(Outcome.PASS, report.scenario("ingest.invalid-telemetry-never-reaches-the-core").outcome());
        assertEquals(Outcome.PASS, report.scenario("ingest.an-identical-redelivery-is-stored-once").outcome());
        for (String scenario : new String[]{"egress.confirmed-states-its-evidence-and-sends-the-stored-bytes", "egress.not-sent-only-with-proof-and-a-stable-code", "egress.doubt-is-uncertain"}) {
            assertEquals(Outcome.NOT_SUPPORTED, report.scenario(scenario).outcome(), scenario);
        }
        assertFalse(report.conformantCapabilities().contains("COMMAND_EGRESS"));

        // P5-082: the descriptor lacks CommandEgress, the registry and the diagnostics endpoint say ingest-only, and command acceptance fails closed
        assertFalse(httpAdapter.capabilities().contains(AdapterCapability.COMMAND_EGRESS));
        assertFalse(httpAdapter instanceof com.nexus.adapter.spi.CommandEgress);
        assertTrue(registry.reports().stream().filter(r -> r.id().equals("http-webhook")).findFirst().orElseThrow().ingestOnly());
        mvc.perform(get("/api/v1/adapters")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='http-webhook')].ingestOnly").value(true));
        UUID device = device(TransportKind.HTTP_WEBHOOK);
        var decision = eligibility.eligible(device, Instant.now());
        assertFalse(decision.eligible());
        assertEquals(HttpTransportEligibility.EGRESS_NOT_SUPPORTED, decision.code());
        assertEquals(0, count("SELECT COUNT(*) FROM action_history WHERE device_id = ?", device), "no command side effect exists for an HTTP device");
    }

    @Test
    void p5_106_theSimulatorPassesTheKitForItsCommandEgressAndDecodesNothing() throws Exception {
        ConformanceReport report = ConformanceKit.run(simulatorSubject());
        assertConforms(report);
        assertEquals(java.util.Set.of(AdapterCapability.LIFECYCLE, AdapterCapability.COMMAND_EGRESS), report.declared());
        assertEquals(Outcome.PASS, report.scenario("egress.confirmed-states-its-evidence-and-sends-the-stored-bytes").outcome());
        assertEquals(Outcome.PASS, report.scenario("egress.doubt-is-uncertain").outcome());
        assertEquals(Outcome.PASS, report.scenario("ingest.valid-telemetry-reaches-the-core-exactly-once").outcome());
        assertEquals(Outcome.PASS, report.scenario("ingest.valid-acknowledgement-reaches-the-core-exactly-once").outcome());
        assertEquals(Outcome.PASS, report.scenario("ingest.an-identical-redelivery-is-stored-once").outcome());
        assertEquals(Outcome.NOT_SUPPORTED, report.scenario("telemetry.shared-cases").outcome());
        assertEquals(Outcome.NOT_APPLICABLE, report.scenario("ingest.invalid-telemetry-never-reaches-the-core").outcome());
        // the in-process device being delivered to proves nothing about a transport to real hardware, and the transport still fails closed for acceptance
        UUID device = device(TransportKind.SIMULATOR);
        simulator.register(new FirmwareLikeDevice(device, Clock.systemUTC()));
        var decision = eligibility.eligible(device, Instant.now());
        assertFalse(decision.eligible());
        assertEquals(SimulatorTransportEligibility.DELIVERY_NOT_ROUTED, decision.code());
    }

    @Test
    void p5_108_theTestOnlyFourthAdapterFeedsTheLiveCoreThroughTheTelemetryPortAndPassesTheSameKit() throws Exception {
        ConformanceReport report = ConformanceKit.run(serialSubject());
        assertConforms(report);
        assertEquals(Outcome.PASS, report.scenario("telemetry.shared-cases").outcome());
        assertEquals(Outcome.PASS, report.scenario("ingest.an-identical-redelivery-is-stored-once").outcome(), "the real inbox deduplicates samples of the fourth transport too");
        assertEquals(Outcome.NOT_SUPPORTED, report.scenario("egress.confirmed-states-its-evidence-and-sends-the-stored-bytes").outcome());

        // data from the fourth transport reaches the Digital Twin through the unchanged core, with no Twin or Rule code aware of it
        SerialLikeAdapter adapter = (SerialLikeAdapter) serialSubject().adapter();
        UUID device = device(TransportKind.SIMULATOR);
        adapter.bind("ttyUSB0", device);
        IntakeResult result = adapter.onFrame("ttyUSB0", (sample(device, "serial-twin-1") + "\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(result.isAccepted());
        assertEquals(1, stored(device));
        assertEquals(1, count("SELECT COUNT(*) FROM digital_twins WHERE device_id = ?", device));
        // a frame for another device than the one bound to the port is refused before the core
        UUID other = device(TransportKind.SIMULATOR);
        int baseline = telemetryBaseline();
        assertEquals(RejectionCode.DEVICE_MISMATCH, ((IntakeResult.Refused) adapter.onFrame("ttyUSB0", (sample(other, "serial-twin-2") + "\n").getBytes(StandardCharsets.UTF_8))).rejection().code());
        assertEquals(0, telemetryCallsSince(baseline, other));
        assertEquals(0, stored(other));
    }

    @Test
    void p5_109_theTestTransportIsNotSelectableInProduction() throws Exception {
        UUID device = device(TransportKind.MQTT_JSON);
        for (String candidate : new String[]{"SERIAL_TEST", "serial-test", "SERIAL", "UART"}) {
            mvc.perform(put("/api/v1/devices/" + device + "/transport").contentType(MediaType.APPLICATION_JSON).content("{\"transport\":\"" + candidate + "\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Unknown transport; allowed: MQTT_JSON, HTTP_WEBHOOK, SIMULATOR"));
        }
        assertEquals("MQTT_JSON", jdbc.queryForObject("SELECT transport FROM devices WHERE id = ?", String.class, device), "the assignment did not change");
        // the database refuses it too: the V41 CHECK constraint lists exactly the three production transports
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE devices SET transport = 'SERIAL_TEST' WHERE id = ?", device));
        // the running registry knows exactly the production adapters: the fourth adapter is not a bean and is not listed
        List<String> ids = registry.adapters().stream().map(a -> a.id()).toList();
        assertEquals(List.of("http-webhook", "mqtt-json", "simulator"), ids);
        String body = mvc.perform(get("/api/v1/adapters")).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("serial"));
    }

    // ------------------------------------------------------------------------------------------------ mixed fleet

    @Test
    void p5_122_aMixedFleetRunsSideBySideEachRoutedByItsAssignmentAndNoneAffectsAnother() throws Exception {
        UUID mqttDevice = device(TransportKind.MQTT_JSON);
        UUID httpDevice = device(TransportKind.HTTP_WEBHOOK);
        UUID simulatedDevice = device(TransportKind.SIMULATOR);
        String httpKey = admin().create(httpDevice).key();
        simulator.register(new FirmwareLikeDevice(simulatedDevice, Clock.systemUTC()));
        MqttClient publisher = connect();

        for (int round = 1; round <= 3; round++) {
            MqttMessage message = new MqttMessage(sample(mqttDevice, "mixed-m-" + round).getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            publisher.publish("nexus/devices/" + mqttDevice + "/telemetry", message);
            assertEquals(200, mvc.perform(post(TELEMETRY.formatted(httpDevice)).header("Authorization", "Bearer " + httpKey).contentType(MediaType.APPLICATION_JSON)
                    .content(sample(httpDevice, "mixed-h-" + round))).andReturn().getResponse().getStatus());
            assertTrue(simulator.publishTelemetry(simulatedDevice, new HttpJsonDecoder().decodeTelemetry(sample(simulatedDevice, "mixed-s-" + round).getBytes(StandardCharsets.UTF_8), null) instanceof Decoded.Accepted<TelemetryPayload> a ? a.value() : null).isAccepted());
        }
        await(() -> stored(mqttDevice) == 3, "the MQTT device's three samples");
        assertEquals(3, stored(httpDevice));
        assertEquals(3, stored(simulatedDevice));
        for (UUID d : List.of(mqttDevice, httpDevice, simulatedDevice)) assertEquals(1, count("SELECT COUNT(*) FROM digital_twins WHERE device_id = ?", d), "one twin per device");

        // each device is served only by its assigned transport, and the refusals leave every other device untouched
        int baseline = telemetryBaseline();
        assertEquals(403, mvc.perform(post(TELEMETRY.formatted(mqttDevice)).header("Authorization", "Bearer " + httpKey).contentType(MediaType.APPLICATION_JSON).content(sample(mqttDevice, "x-1"))).andReturn().getResponse().getStatus());
        assertTrue(simulator.publishTelemetry(httpDevice, ((Decoded.Accepted<TelemetryPayload>) new HttpJsonDecoder()
                .decodeTelemetry(sample(httpDevice, "x-2").getBytes(StandardCharsets.UTF_8), null)).value()) instanceof IntakeResult.Refused, "the simulator transport does not serve an HTTP-assigned device");
        MqttMessage crossed = new MqttMessage(sample(httpDevice, "x-3").getBytes(StandardCharsets.UTF_8));
        crossed.setQos(1);
        publisher.publish("nexus/devices/" + httpDevice + "/telemetry", crossed);
        MqttMessage sentinel = new MqttMessage(sample(mqttDevice, "mixed-m-4").getBytes(StandardCharsets.UTF_8));
        sentinel.setQos(1);
        publisher.publish("nexus/devices/" + mqttDevice + "/telemetry", sentinel);
        await(() -> stored(mqttDevice) == 4, "the MQTT device to continue after a refused cross-transport message");
        assertEquals(0, telemetryCallsSince(baseline, httpDevice), "an HTTP-assigned device's MQTT message never reached the core");
        assertEquals(3, stored(httpDevice));
        assertEquals(3, stored(simulatedDevice));

        // stopping one transport affects only its own devices
        simulator.stop();
        assertEquals(RejectionCode.UNAUTHENTICATED, ((IntakeResult.Refused) simulator.publishTelemetry(simulatedDevice, new TelemetryPayload(simulatedDevice, Instant.now(), "T", 1.0, "U", null, null, "stopped-1"))).rejection().code());
        assertEquals(200, mvc.perform(post(TELEMETRY.formatted(httpDevice)).header("Authorization", "Bearer " + httpKey).contentType(MediaType.APPLICATION_JSON).content(sample(httpDevice, "mixed-h-4"))).andReturn().getResponse().getStatus());
        assertEquals(4, stored(httpDevice));
        assertEquals(3, stored(simulatedDevice));
        simulator.start();

        // revoking the HTTP credential affects only the HTTP device
        admin().list(httpDevice).forEach(row -> admin().revoke(row.lookupId()));
        assertEquals(401, mvc.perform(post(TELEMETRY.formatted(httpDevice)).header("Authorization", "Bearer " + httpKey).contentType(MediaType.APPLICATION_JSON).content(sample(httpDevice, "mixed-h-5"))).andReturn().getResponse().getStatus());
        MqttMessage after = new MqttMessage(sample(mqttDevice, "mixed-m-5").getBytes(StandardCharsets.UTF_8));
        after.setQos(1);
        publisher.publish("nexus/devices/" + mqttDevice + "/telemetry", after);
        await(() -> stored(mqttDevice) == 5, "the MQTT device to continue after the HTTP credential was revoked");
        assertTrue(simulator.publishTelemetry(simulatedDevice, new TelemetryPayload(simulatedDevice, Instant.now(), "TEMPERATURE", 22.0, "CELSIUS", null, null, "mixed-s-9")).isAccepted());

        // command eligibility is per assignment: MQTT follows the device's retained birth and the broker connection, the other two fail closed with their documented reasons
        assertEquals("DEVICE_OFFLINE", eligibility.eligible(mqttDevice, Instant.now()).code(), "no birth message yet");
        MqttMessage birth = new MqttMessage("online".getBytes(StandardCharsets.UTF_8));
        birth.setQos(1);
        birth.setRetained(true);
        publisher.publish("nexus/devices/" + mqttDevice + "/status", birth);
        await(() -> eligibility.eligible(mqttDevice, Instant.now()).eligible(), "the MQTT device to be eligible once online with the broker connected");
        MqttMessage clear = new MqttMessage(new byte[0]);
        clear.setRetained(true);
        publisher.publish("nexus/devices/" + mqttDevice + "/status", clear);
        assertEquals(HttpTransportEligibility.EGRESS_NOT_SUPPORTED, eligibility.eligible(httpDevice, Instant.now()).code());
        assertEquals(SimulatorTransportEligibility.DELIVERY_NOT_ROUTED, eligibility.eligible(simulatedDevice, Instant.now()).code());
    }

    // ------------------------------------------------------------------------------------------------ negative safety: refusals leave no side effect

    @Test
    void simulatorNegativePathsNeverReachTheCore() {
        UUID registered = device(TransportKind.SIMULATOR);
        simulator.register(new FirmwareLikeDevice(registered, Clock.systemUTC()));
        UUID unregistered = device(TransportKind.SIMULATOR);
        UUID wrongAssignment = device(TransportKind.MQTT_JSON);
        simulator.register(new FirmwareLikeDevice(wrongAssignment, Clock.systemUTC()));
        int baseline = telemetryBaseline();
        TelemetryPayload sample = new TelemetryPayload(registered, Instant.now(), "TEMPERATURE", 1.0, "CELSIUS", null, null, "neg-1");
        assertEquals(RejectionCode.FORBIDDEN_DEVICE, ((IntakeResult.Refused) simulator.publishTelemetry(unregistered, new TelemetryPayload(unregistered, Instant.now(), "T", 1.0, "U", null, null, "neg-2"))).rejection().code());
        assertEquals(RejectionCode.TRANSPORT_MISMATCH, ((IntakeResult.Refused) simulator.publishTelemetry(wrongAssignment, new TelemetryPayload(wrongAssignment, Instant.now(), "T", 1.0, "U", null, null, "neg-3"))).rejection().code());
        simulator.stop();
        assertEquals(RejectionCode.UNAUTHENTICATED, ((IntakeResult.Refused) simulator.publishTelemetry(registered, sample)).rejection().code());
        simulator.start();
        assertEquals(0, telemetryCallsSince(baseline, unregistered) + telemetryCallsSince(baseline, wrongAssignment) + telemetryCallsSince(baseline, registered));
        assertEquals(0, stored(unregistered) + stored(wrongAssignment) + stored(registered));
        // and a refused acknowledgement changes no command
        UUID command = pendingCommand(wrongAssignment);
        assertEquals(RejectionCode.TRANSPORT_MISMATCH, ((IntakeResult.Refused) simulator.publishAcknowledgement(wrongAssignment,
                new AckPayload(command, AckPayload.Status.ACCEPTED, Instant.now(), null))).rejection().code());
        assertFalse(acknowledged(command));
    }

    @Test
    void httpNegativePathsNeverReachTheCoreOrTheCommandTables() throws Exception {
        UUID a = device(TransportKind.HTTP_WEBHOOK), b = device(TransportKind.HTTP_WEBHOOK);
        String keyA = admin().create(a).key();
        admin().create(b);
        UUID commandOfB = pendingCommand(b);
        int baseline = telemetryBaseline();
        // another device's path, a missing and a wrong credential, an acknowledgement for another device's command
        MvcResult cross = mvc.perform(post(TELEMETRY.formatted(b)).header("Authorization", "Bearer " + keyA).contentType(MediaType.APPLICATION_JSON).content(sample(b, "n-1"))).andReturn();
        assertEquals(403, cross.getResponse().getStatus());
        assertEquals(401, mvc.perform(post(TELEMETRY.formatted(a)).contentType(MediaType.APPLICATION_JSON).content(sample(a, "n-2"))).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(post(TELEMETRY.formatted(a)).header("Authorization", "Bearer nxk1_" + "0".repeat(24) + "_" + "A".repeat(43)).contentType(MediaType.APPLICATION_JSON).content(sample(a, "n-3"))).andReturn().getResponse().getStatus());
        mvc.perform(post(ACKS.formatted(a)).header("Authorization", "Bearer " + keyA).contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandId\":\"" + commandOfB + "\",\"status\":\"ACCEPTED\",\"timestamp\":1767225600}")).andReturn();
        assertEquals(0, telemetryCallsSince(baseline, a) + telemetryCallsSince(baseline, b));
        assertEquals(0, stored(a) + stored(b));
        assertFalse(acknowledged(commandOfB), "a device cannot acknowledge another device's command");
        assertEquals(0, count("SELECT COUNT(*) FROM action_history WHERE device_id = ? AND ack_status IS NOT NULL", b));
        assertNotNull(keyA);
    }

    @Test
    void anMqttMessageForAnHttpAssignedDeviceNeverReachesTheCore() throws Exception {
        UUID httpDevice = device(TransportKind.HTTP_WEBHOOK);
        UUID mqttDevice = device(TransportKind.MQTT_JSON);
        MqttClient publisher = connect();
        int baseline = telemetryBaseline();
        MqttMessage crossed = new MqttMessage(sample(httpDevice, "neg-m-1").getBytes(StandardCharsets.UTF_8));
        crossed.setQos(1);
        publisher.publish("nexus/devices/" + httpDevice + "/telemetry", crossed);
        UUID command = pendingCommand(httpDevice);
        MqttMessage ack = new MqttMessage(("{\"commandId\":\"" + command + "\",\"status\":\"ACCEPTED\",\"timestamp\":" + Instant.now().getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
        ack.setQos(1);
        publisher.publish("nexus/devices/" + httpDevice + "/commands/ack", ack);
        MqttMessage sentinel = new MqttMessage(sample(mqttDevice, "neg-m-2").getBytes(StandardCharsets.UTF_8));
        sentinel.setQos(1);
        publisher.publish("nexus/devices/" + mqttDevice + "/telemetry", sentinel);
        await(() -> stored(mqttDevice) == 1, "the sentinel behind the refused messages");
        assertEquals(0, telemetryCallsSince(baseline, httpDevice));
        assertEquals(0, stored(httpDevice));
        assertFalse(acknowledged(command), "an MQTT acknowledgement for an HTTP-assigned device's command changes nothing");
    }
}
