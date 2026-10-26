package com.nexus.adapter;

import com.nexus.adapter.core.AdapterRegistry;
import com.nexus.adapter.core.HttpContactWindow;
import com.nexus.adapter.core.HttpTransportEligibility;
import com.nexus.adapter.credentials.CredentialAdmin;
import com.nexus.adapter.http.HttpWebhookAdapter;
import com.nexus.adapter.http.KeyFormat;
import com.nexus.adapter.http.KeyHasher;
import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.Eligibility;
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
import com.nexus.telemetry.application.TelemetryService;
import io.micrometer.core.instrument.MeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP/webhook adapter on the real application, PostgreSQL and Mosquitto (ledger P5-019, P5-026, P5-049 to P5-063, P5-139 to P5-141, P5-152):
 * credentials, authorisation, bounds, strict decoding through the unchanged core, the status mapping, valid contacts and the fail-closed
 * command eligibility. Keys are issued by the same CredentialAdmin the tool uses; the backend never creates one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class HttpAdapterIntegrationTest {
    static final String PEPPER = "http-test-pepper-0123456789abcdef";
    static final String TELEMETRY = "/api/v1/device-adapter/devices/%s/telemetry";
    static final String ACKS = "/api/v1/device-adapter/devices/%s/acknowledgements";
    static final String STATUS = "/api/v1/device-adapter/devices/%s/status";

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
        registry.add("mqtt.client.id", () -> "p54-app-" + UUID.randomUUID());
        registry.add("nexus.adapter.http.enabled", () -> "true");
        registry.add("NEXUS_DEVICE_KEY_PEPPER", () -> PEPPER);
        registry.add("server.address", () -> "127.0.0.1");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdapterRegistry registry;
    @Autowired HttpWebhookAdapter adapter;
    @Autowired TransportEligibility eligibility;
    @Autowired DeviceConnectivityRegistry connectivity;
    @Autowired MeterRegistry meters;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @SpyBean TelemetryService telemetryService;

    private final List<MqttClient> clients = new ArrayList<>();

    @AfterEach
    void close() {
        adapter.start();
        Mockito.reset(telemetryService);
        for (MqttClient c : clients) {
            try { if (c.isConnected()) c.disconnectForcibly(0, 0, false); c.close(true); } catch (MqttException ignored) { }
        }
    }

    // ------------------------------------------------------------------ fixtures

    private UUID device(TransportKind transport) {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Http", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "test", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "test", now, now));
        Device d = new Device(UUID.randomUUID(), space, "M", DeviceType.CUSTOM, "NEXUS", "TEST", "P54-" + UUID.randomUUID(), null, DeviceMode.SIMULATED);
        d.update("M", DeviceStatus.ACTIVE, null);
        UUID id = devices.save(d).getId();
        jdbc.update("UPDATE devices SET transport = ? WHERE id = ?", transport.name(), id);
        return id;
    }

    private UUID httpDevice() {
        return device(TransportKind.HTTP_WEBHOOK);
    }

    private CredentialAdmin admin() {
        return new CredentialAdmin(() -> DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()),
                new KeyHasher(PEPPER.getBytes(StandardCharsets.UTF_8)), new SecureRandom(), "tester");
    }

    private String key(UUID device) {
        return admin().create(device).key();
    }

    private static String sample(UUID device, String extra) {
        return "{\"deviceId\":\"" + device + "\",\"timestamp\":\"2026-10-17T12:00:00Z\",\"sensorType\":\"TEMPERATURE\",\"value\":21.5,\"unit\":\"CELSIUS\"" + extra + "}";
    }

    private MockHttpServletRequestBuilder request(String pathTemplate, UUID device, String key, String contentType, String body) {
        MockHttpServletRequestBuilder builder = post(pathTemplate.formatted(device)).content(body.getBytes(StandardCharsets.UTF_8));
        if (contentType != null) builder.contentType(contentType);
        if (key != null) builder.header("Authorization", "Bearer " + key);
        return builder;
    }

    private MvcResult send(String pathTemplate, UUID device, String key, String body) throws Exception {
        return mvc.perform(request(pathTemplate, device, key, MediaType.APPLICATION_JSON_VALUE, body)).andReturn();
    }

    private int statusOf(String pathTemplate, UUID device, String key, String body) throws Exception {
        return send(pathTemplate, device, key, body).getResponse().getStatus();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private java.sql.Timestamp lastContact(UUID device) {
        return jdbc.queryForObject("SELECT max(last_valid_contact_at) FROM device_ingestion_credentials WHERE device_id = ?", java.sql.Timestamp.class, device);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    // ------------------------------------------------------------------ authentication (P5-049 to P5-052, P5-060, P5-061)

    @Test
    void p5_049_aMissingCredentialIsRejectedGenerically() throws Exception {
        UUID device = httpDevice();
        String issued = key(device);
        for (String header : new String[]{null, "", "Bearer", "Bearer ", "Basic dXNlcjpwYXNz", "bearer " + issued, "Bearer  " + issued}) {
            var builder = post(TELEMETRY.formatted(device)).contentType(MediaType.APPLICATION_JSON).content(sample(device, ""));
            if (header != null) builder.header("Authorization", header);
            mvc.perform(builder).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHENTICATED"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("WWW-Authenticate", "Bearer"));
        }
        assertEquals(0, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device));
    }

    @Test
    void p5_050_anInvalidCredentialIsRejectedWithTheSameAnswerWhateverTheReason() throws Exception {
        UUID device = httpDevice();
        String good = key(device);
        KeyFormat.Parsed parsed = KeyFormat.parse(good).orElseThrow();
        String wrongSecret = KeyFormat.format(parsed.lookupId(), "A".repeat(KeyFormat.SECRET_LENGTH));
        String unknownLookup = KeyFormat.format("0".repeat(KeyFormat.LOOKUP_LENGTH), parsed.secret());
        String truncated = good.substring(0, good.length() - 1);
        String injection = "nxk1_' OR '1'='1";
        List<String> bodies = new ArrayList<>();
        for (String bad : new String[]{wrongSecret, unknownLookup, truncated, injection, good + "x", good.toUpperCase()}) {
            MvcResult result = send(TELEMETRY, device, bad, sample(device, ""));
            assertEquals(401, result.getResponse().getStatus());
            bodies.add(result.getResponse().getContentAsString() + "|" + result.getResponse().getHeader("WWW-Authenticate"));
        }
        assertEquals(1, new java.util.HashSet<>(bodies).size(), "no oracle: every failure answers identically");
        // the key in a query parameter is not a credential
        mvc.perform(post(TELEMETRY.formatted(device) + "?key=" + good).contentType(MediaType.APPLICATION_JSON).content(sample(device, ""))).andExpect(status().isUnauthorized());
        assertEquals(200, statusOf(TELEMETRY, device, good, sample(device, "")), "the genuine key still works");
    }

    @Test
    void p5_051_aRevokedCredentialIsRejectedOnTheVeryNextRequestAndRevocationIsFinal() throws Exception {
        UUID device = httpDevice();
        CredentialAdmin.Issued issued = admin().create(device);
        assertEquals(200, statusOf(TELEMETRY, device, issued.key(), sample(device, ",\"messageId\":\"rev-1\"")));
        admin().revoke(issued.lookupId());
        assertEquals(401, statusOf(TELEMETRY, device, issued.key(), sample(device, ",\"messageId\":\"rev-2\"")), "no cache: the next request is refused");
        CredentialAdmin.Issued fresh = admin().create(device);
        assertEquals(200, statusOf(TELEMETRY, device, fresh.key(), sample(device, ",\"messageId\":\"rev-3\"")));
        assertEquals(401, statusOf(TELEMETRY, device, issued.key(), sample(device, ",\"messageId\":\"rev-4\"")), "a new credential does not revive the revoked one");
    }

    @Test
    void p5_052_p5_060_aRetiringCredentialWorksUntilItsInstantThenStopsWhileTheNewOneKeepsTheDeviceOnline() throws Exception {
        UUID device = httpDevice();
        CredentialAdmin.Issued old = admin().create(device);
        CredentialAdmin.Issued fresh = admin().rotate(device, Duration.ofHours(1));
        assertEquals(200, statusOf(TELEMETRY, device, old.key(), sample(device, ",\"messageId\":\"rot-1\"")), "the old key works while it is RETIRING");
        assertEquals(200, statusOf(TELEMETRY, device, fresh.key(), sample(device, ",\"messageId\":\"rot-2\"")), "and so does the new one: no outage during an update");
        jdbc.update("UPDATE device_ingestion_credentials SET retire_at = now() - interval '1 second' WHERE lookup_id = ?", old.lookupId());
        assertEquals(401, statusOf(TELEMETRY, device, old.key(), sample(device, ",\"messageId\":\"rot-3\"")), "the instant has passed (database clock)");
        assertEquals(200, statusOf(TELEMETRY, device, fresh.key(), sample(device, ",\"messageId\":\"rot-4\"")));
    }

    // ------------------------------------------------------------------ authorisation (P5-056, P5-057)

    @Test
    void p5_056_aCredentialCannotSubmitForAnotherDeviceInAnyCombination() throws Exception {
        UUID a = httpDevice(), b = httpDevice();
        String keyA = key(a);
        key(b);
        // path names B: the credential of A
        mvc.perform(request(TELEMETRY, b, keyA, MediaType.APPLICATION_JSON_VALUE, sample(b, ""))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("FORBIDDEN_DEVICE"));
        // path names A, payload names B
        mvc.perform(request(TELEMETRY, a, keyA, MediaType.APPLICATION_JSON_VALUE, sample(b, ""))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("FORBIDDEN_DEVICE"));
        // an acknowledgement and a status on B's path
        mvc.perform(request(ACKS, b, keyA, MediaType.APPLICATION_JSON_VALUE, "{\"commandId\":\"" + UUID.randomUUID() + "\",\"status\":\"ACCEPTED\",\"timestamp\":1767225600}")).andExpect(status().isForbidden());
        mvc.perform(request(STATUS, b, keyA, MediaType.TEXT_PLAIN_VALUE, "online")).andExpect(status().isForbidden());
        // a path that is not a UUID
        mvc.perform(post("/api/v1/device-adapter/devices/not-a-uuid/telemetry").header("Authorization", "Bearer " + keyA).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        assertEquals(0, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id IN (?, ?)", a, b));
        assertEquals(200, statusOf(TELEMETRY, a, keyA, sample(a, "")));
    }

    @Test
    void p5_057_aDeviceAssignedToMqttCannotUseTheHttpPathAndNothingIsProcessed() throws Exception {
        UUID mqttDevice = device(TransportKind.MQTT_JSON);
        String key = key(mqttDevice);
        mvc.perform(request(TELEMETRY, mqttDevice, key, MediaType.APPLICATION_JSON_VALUE, sample(mqttDevice, "")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("TRANSPORT_MISMATCH"));
        assertEquals(0, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", mqttDevice));
        assertEquals(null, lastContact(mqttDevice), "a refused request is not a contact");
        Mockito.verify(telemetryService, Mockito.never()).processTelemetry(any(), any());
    }

    // ------------------------------------------------------------------ bounds (P5-058, P5-059) and TLS guard

    @Test
    void p5_058_anOversizedBodyIsRefusedBeforeItIsParsed() throws Exception {
        UUID device = httpDevice();
        String key = key(device);
        String padded = sample(device, "") + " ".repeat(8192);
        mvc.perform(request(TELEMETRY, device, key, MediaType.APPLICATION_JSON_VALUE, padded)).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error").value("PAYLOAD_TOO_LARGE"));
        // garbage over the limit is 413, not 400: it was never parsed
        mvc.perform(request(TELEMETRY, device, key, MediaType.APPLICATION_JSON_VALUE, "x".repeat(9000))).andExpect(status().isPayloadTooLarge());
        String exact = sample(device, "");
        exact = exact + " ".repeat(8192 - exact.length());
        assertEquals(8192, exact.length());
        assertEquals(200, statusOf(TELEMETRY, device, key, exact), "exactly the limit is accepted");
        assertEquals(413, statusOf(TELEMETRY, device, key, exact + " "), "one byte more is not");
        mvc.perform(request(TELEMETRY, device, key, "text/plain", sample(device, ""))).andExpect(status().isUnsupportedMediaType());
        mvc.perform(request(TELEMETRY, device, key, null, sample(device, ""))).andExpect(status().isUnsupportedMediaType());
        mvc.perform(request(TELEMETRY, device, key, "application/json; charset=ISO-8859-1", sample(device, ""))).andExpect(status().isUnsupportedMediaType());
        mvc.perform(request(TELEMETRY, device, key, "application/json; charset=utf-8", sample(device, ",\"messageId\":\"ct-1\""))).andExpect(status().isOk());
    }

    @Test
    void p5_059_perDeviceConcurrencyIsBoundedAndTheNextRequestIsRefusedNotQueued() throws Exception {
        UUID device = httpDevice();
        String key = key(device);
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            com.nexus.telemetry.api.dto.TelemetryRequest request = invocation.getArgument(0);
            if (request.deviceId().equals(device)) {
                entered.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("not released");
            }
            return invocation.callRealMethod();
        }).when(telemetryService).processTelemetry(any(), any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> inFlight = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String id = "conc-" + i;
                inFlight.add(pool.submit(() -> statusOf(TELEMETRY, device, key, sample(device, ",\"messageId\":\"" + id + "\""))));
            }
            assertTrue(entered.await(30, TimeUnit.SECONDS), "two requests are inside the core");
            mvc.perform(request(TELEMETRY, device, key, MediaType.APPLICATION_JSON_VALUE, sample(device, ",\"messageId\":\"conc-3\"")))
                    .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("TOO_MANY_REQUESTS"));
            UUID other = httpDevice();
            assertEquals(200, statusOf(TELEMETRY, other, key(other), sample(other, "")), "another device is not affected");
            release.countDown();
            for (Future<Integer> f : inFlight) assertEquals(200, f.get(30, TimeUnit.SECONDS));
            assertEquals(200, statusOf(TELEMETRY, device, key, sample(device, ",\"messageId\":\"conc-4\"")), "the permits were returned");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ the core behind the adapter (P5-062, P5-019, P5-063)

    @Test
    void p5_062_redeliveredTelemetryIsDeduplicatedByMessageIdOverHttpAndAConflictIsReported() throws Exception {
        UUID device = httpDevice();
        String key = key(device);
        String body = sample(device, ",\"messageId\":\"http-dedupe-1\"");
        assertEquals(200, statusOf(TELEMETRY, device, key, body));
        assertEquals(200, statusOf(TELEMETRY, device, key, body), "an identical redelivery is accepted and suppressed");
        assertEquals(1, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device));
        mvc.perform(request(TELEMETRY, device, key, MediaType.APPLICATION_JSON_VALUE, body.replace("21.5", "99.0"))).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("DUPLICATE_CONFLICT"));
        assertEquals(1, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device));
        assertEquals(1, count("SELECT COUNT(*) FROM inbound_message_dedupe WHERE device_id = ? AND message_id = 'http-dedupe-1' AND source = 'HTTP'", device));
    }

    @Test
    void p5_019_theVersionHeaderAndBodyAreResolvedByTheContractRules() throws Exception {
        UUID device = httpDevice();
        String key = key(device);
        var plain = sample(device, "");
        var versioned = sample(device, ",\"contractVersion\":\"1.0\",\"messageId\":\"v-1\"");
        mvc.perform(request(TELEMETRY, device, key, MediaType.APPLICATION_JSON_VALUE, versioned).header("X-Nexus-Contract-Version", "1.1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("CONTRACT_VERSION_CONFLICT"));
        mvc.perform(request(TELEMETRY, device, key, MediaType.APPLICATION_JSON_VALUE, versioned).header("X-Nexus-Contract-Version", "1.0")).andExpect(status().isOk());
        mvc.perform(request(TELEMETRY, device, key, MediaType.APPLICATION_JSON_VALUE, plain).header("X-Nexus-Contract-Version", "2.0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("UNSUPPORTED_CONTRACT_MAJOR"));
        mvc.perform(request(TELEMETRY, device, key, MediaType.APPLICATION_JSON_VALUE, plain).header("X-Nexus-Contract-Version", "nope"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_CONTRACT_VERSION"));
        mvc.perform(request(TELEMETRY, device, key, MediaType.APPLICATION_JSON_VALUE, sample(device, ",\"contractVersion\":\"1.4\",\"requires\":[\"frobnicate\"]")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("UNSUPPORTED_REQUIRED_FEATURE"));
        mvc.perform(request(TELEMETRY, device, key, MediaType.APPLICATION_JSON_VALUE, sample(device, ",\"contractVersion\":\"1.4\",\"futureField\":1,\"messageId\":\"v-2\"")))
                .andExpect(status().isOk());
    }

    @Test
    void p5_063_theStatusMappingIsCompleteAndStable() throws Exception {
        UUID device = httpDevice();
        String key = key(device);
        record Case(String name, String path, String contentType, String body, int status, String error) {}
        String ack = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"status\":\"ACCEPTED\",\"timestamp\":1767225600}";
        List<Case> cases = List.of(
                new Case("accepted telemetry", TELEMETRY, "application/json", sample(device, ",\"messageId\":\"map-1\""), 200, null),
                new Case("acknowledgement for an unknown command is dropped by the core and accepted", ACKS, "application/json", ack, 200, null),
                new Case("status claim", STATUS, "text/plain", "online", 200, null),
                new Case("malformed json", TELEMETRY, "application/json", "{", 400, "MALFORMED_JSON"),
                new Case("invalid fields", TELEMETRY, "application/json", "{\"timestamp\":\"2026-01-01T00:00:00Z\",\"value\":1}", 400, "INVALID_FIELDS"),
                new Case("invalid message id", TELEMETRY, "application/json", sample(device, ",\"messageId\":\"a b\""), 400, "INVALID_MESSAGE_ID"),
                new Case("invalid legacy payload", TELEMETRY, "application/json", "{\"deviceId\":\"" + device + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"value\":\"x\"}", 400, "INVALID_LEGACY_PAYLOAD"),
                new Case("invalid acknowledgement", ACKS, "application/json", "{\"commandId\":\"x\"}", 400, "INVALID_FIELDS"),
                new Case("not a status", STATUS, "text/plain", "Online", 400, "INVALID_FIELDS"),
                new Case("status posted as json", STATUS, "application/json", "online", 415, "UNSUPPORTED_MEDIA_TYPE"),
                new Case("telemetry posted as text", TELEMETRY, "text/plain", sample(device, ""), 415, "UNSUPPORTED_MEDIA_TYPE"));
        for (Case c : cases) {
            MvcResult result = mvc.perform(request(c.path(), device, key, c.contentType(), c.body())).andReturn();
            assertEquals(c.status(), result.getResponse().getStatus(), c.name());
            if (c.error() != null) assertTrue(result.getResponse().getContentAsString().contains("\"" + c.error() + "\""), c.name() + ": " + result.getResponse().getContentAsString());
            assertFalse(result.getResponse().getContentAsString().contains(key), "nothing the caller sent is reflected");
        }
        // an unknown telemetry device is permanently invalid input in the core and surfaces as a fixed client error, never a 500
        UUID ghost = httpDevice();
        String ghostKey = key(ghost);
        jdbc.update("UPDATE device_ingestion_credentials SET status = 'REVOKED', revoked_at = now() WHERE device_id = ?", ghost);
        assertEquals(401, statusOf(TELEMETRY, ghost, ghostKey, sample(ghost, "")));
        // an inactive device holds a valid credential but is refused
        UUID inactive = httpDevice();
        String inactiveKey = key(inactive);
        jdbc.update("UPDATE devices SET status = 'MAINTENANCE' WHERE id = ?", inactive);
        mvc.perform(request(TELEMETRY, inactive, inactiveKey, MediaType.APPLICATION_JSON_VALUE, sample(inactive, ""))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("FORBIDDEN_DEVICE"));
        // a stopped adapter answers 503 and nothing else changes
        adapter.stop();
        mvc.perform(request(TELEMETRY, device, key, MediaType.APPLICATION_JSON_VALUE, sample(device, ",\"messageId\":\"map-2\""))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("ADAPTER_STOPPED"));
        adapter.start();
        assertEquals(200, statusOf(TELEMETRY, device, key, sample(device, ",\"messageId\":\"map-2\"")));
        // an infrastructure failure of the core is 503 with Retry-After and acknowledges nothing: the device retries and the inbox makes it safe
        Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("database down")).when(telemetryService).processTelemetry(any(), any());
        mvc.perform(request(TELEMETRY, device, key, MediaType.APPLICATION_JSON_VALUE, sample(device, ",\"messageId\":\"map-3\""))).andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "5"));
    }

    // ------------------------------------------------------------------ no secrets in logs or URLs (P5-053)

    @Test
    void p5_053_theKeyNeverAppearsInAnAnswerTheLogsTheMetricsOrTheDiagnostics(CapturedOutput output) throws Exception {
        UUID device = httpDevice();
        CredentialAdmin.Issued issued = admin().create(device);
        String secret = KeyFormat.parse(issued.key()).orElseThrow().secret();
        send(TELEMETRY, device, issued.key(), sample(device, ",\"messageId\":\"log-1\""));
        send(TELEMETRY, device, issued.key().substring(0, 40), sample(device, ""));
        send(TELEMETRY, device, KeyFormat.format(issued.lookupId(), "B".repeat(43)), sample(device, ""));
        send(TELEMETRY, httpDevice(), issued.key(), sample(device, ""));
        send(TELEMETRY, device, issued.key(), "{ not json");
        mvc.perform(post(TELEMETRY.formatted(device) + "?access=" + issued.key()).contentType(MediaType.APPLICATION_JSON).content(sample(device, ""))).andReturn();
        assertFalse(output.getAll().contains(secret) || output.getAll().contains(issued.key()), "neither the secret nor the key reaches a log line");
        assertFalse(output.getAll().contains("Bearer "), () -> "the Authorization header is never logged: " + output.getAll().lines().filter(l -> l.contains("Bearer ")).findFirst().orElse(""));
        String diagnostics = mvc.perform(get("/api/v1/adapters")).andReturn().getResponse().getContentAsString();
        assertFalse(diagnostics.contains(secret) || diagnostics.contains(issued.lookupId()) || diagnostics.contains("hash"), "diagnostics carry counters only");
        meters.getMeters().forEach(m -> m.getId().getTags().forEach(t -> assertFalse(t.getValue().contains(secret) || t.getValue().contains(issued.lookupId()), "no meter tag carries credential material")));
    }

    // ------------------------------------------------------------------ valid contacts and eligibility (P5-139, P5-140, P5-141, P5-152)

    @Test
    void p5_140_onlyValidContactsMoveTheLastContactInstantAndP5_141ItIsNotConnectivity() throws Exception {
        UUID device = httpDevice();
        String key = key(device);
        var connectivityBefore = connectivity.get(device);
        long transitionsBefore = count("SELECT COUNT(*) FROM device_connectivity");
        // none of these is a valid contact
        send(TELEMETRY, device, null, sample(device, ""));
        send(TELEMETRY, device, "nxk1_" + "0".repeat(24) + "_" + "A".repeat(43), sample(device, ""));
        send(TELEMETRY, device, key, "{ not json");
        send(TELEMETRY, device, key, sample(device, "").replace("deviceId", "deviceIdx"));
        mvc.perform(request(TELEMETRY, device, key, "text/plain", sample(device, "")));
        mvc.perform(request(TELEMETRY, device, key, MediaType.APPLICATION_JSON_VALUE, sample(device, "") + " ".repeat(9000)));
        UUID other = httpDevice();
        send(TELEMETRY, other, key, sample(other, ""));
        assertEquals(null, lastContact(device), "invalid, malformed, oversized and impersonating requests do not establish a contact");
        // a valid one does, and it is recorded on the database clock
        assertEquals(200, statusOf(TELEMETRY, device, key, sample(device, ",\"messageId\":\"contact-1\"")));
        assertNotNull(lastContact(device));
        assertTrue(Math.abs(Duration.between(lastContact(device).toInstant(), Instant.now()).getSeconds()) < 30);
        // revoked: later requests do not move it
        java.sql.Timestamp recorded = lastContact(device);
        jdbc.update("UPDATE device_ingestion_credentials SET status = 'REVOKED', revoked_at = now() WHERE device_id = ?", device);
        send(TELEMETRY, device, key, sample(device, ",\"messageId\":\"contact-2\""));
        assertEquals(recorded, lastContact(device));
        // P5-141: a contact is not a connectivity observation
        assertEquals(connectivityBefore.isPresent(), connectivity.get(device).isPresent(), "the registry did not learn about the device");
        assertEquals(transitionsBefore, count("SELECT COUNT(*) FROM device_connectivity"), "no connectivity row was written");
        UUID claimant = httpDevice();
        String claimantKey = key(claimant);
        mvc.perform(request(STATUS, claimant, claimantKey, MediaType.TEXT_PLAIN_VALUE, "online")).andExpect(status().isOk());
        assertTrue(connectivity.get(claimant).isEmpty(), "a posted status is a claim: it does not drive the connectivity registry");
    }

    @Test
    void p5_139_theWindowFollowsTheDatabaseClockAndTheProductionRuleStaysFailClosed() throws Exception {
        UUID device = httpDevice();
        String key = key(device);
        HttpContactWindow window = new HttpContactWindowProbe(jdbc).window();
        assertEquals(Duration.ofSeconds(60), window.window());
        assertFalse(window.recentContact(device), "never contacted");
        jdbc.update("UPDATE device_ingestion_credentials SET last_valid_contact_at = now() - interval '59 seconds' WHERE device_id = ?", device);
        assertTrue(window.recentContact(device), "59 s old is inside the 60 s window");
        jdbc.update("UPDATE device_ingestion_credentials SET last_valid_contact_at = now() - interval '61 seconds' WHERE device_id = ?", device);
        assertFalse(window.recentContact(device), "61 s old is outside it");
        jdbc.update("UPDATE device_ingestion_credentials SET last_valid_contact_at = now() WHERE device_id = ?", device);
        assertTrue(window.recentContact(device));
        jdbc.update("UPDATE device_ingestion_credentials SET status = 'REVOKED', revoked_at = now() WHERE device_id = ?", device);
        assertFalse(window.recentContact(device), "a revoked credential's contact does not count");

        HttpTransportEligibility enabled = new HttpContactWindowProbe(jdbc).eligibility(true);
        UUID fresh = httpDevice();
        key(fresh);
        assertEquals(HttpTransportEligibility.NO_RECENT_CONTACT, enabled.eligible(fresh, Instant.now()).code());
        jdbc.update("UPDATE device_ingestion_credentials SET last_valid_contact_at = now() WHERE device_id = ?", fresh);
        assertTrue(enabled.eligible(fresh, Instant.now()).eligible(), "with delivery supported a recent contact makes the device eligible");

        // production: delivery is not supported, so an HTTP device never becomes eligible and no command is accepted for it
        Eligibility production = eligibility.eligible(fresh, Instant.now());
        assertFalse(production.eligible());
        assertEquals(HttpTransportEligibility.EGRESS_NOT_SUPPORTED, production.code());
        assertTrue(production.message().contains("ingest-only"));
    }

    @Test
    void p5_152_outboundHttpIsExplicitlyNotSupportedAndTheLedgerSaysDeferredNotDropped() throws Exception {
        var report = registry.reports().stream().filter(r -> r.id().equals("http-webhook")).findFirst().orElseThrow();
        assertTrue(report.ingestOnly(), "no command egress: the conformance report says not supported");
        assertFalse(report.capabilities().contains(AdapterCapability.COMMAND_EGRESS));
        assertTrue(report.capabilities().contains(AdapterCapability.DEVICE_AUTHENTICATOR));
        assertTrue(registry.capability(TransportKind.HTTP_WEBHOOK, com.nexus.adapter.spi.CommandEgress.class).isEmpty());
        java.nio.file.Path ledger = java.nio.file.Path.of("../architecture/phase5/p5-acceptance-ledger.md");
        if (!java.nio.file.Files.exists(ledger)) ledger = java.nio.file.Path.of("architecture/phase5/p5-acceptance-ledger.md");
        List<String> lines = java.nio.file.Files.readAllLines(ledger);
        List<String> pull = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith("| P5-")) continue;
            int id = Integer.parseInt(line.substring(5, 8));
            if ((id >= 67 && id <= 81) || (id >= 129 && id <= 138)) pull.add(line);
        }
        assertEquals(25, pull.size());
        for (String line : pull) {
            assertTrue(line.contains("| DEFERRED |"), "a pull scenario is DEFERRED, never silently dropped or PLANNED forever: " + line.substring(0, 60));
            assertTrue(line.contains("deferred:") || line.contains("Deferred"), "with its reason: " + line.substring(0, 60));
        }
        mvc.perform(get("/api/v1/adapters")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='http-webhook')].ingestOnly").value(true))
                .andExpect(jsonPath("$[?(@.id=='http-webhook')].transport").value("HTTP_WEBHOOK"));
        UUID device = httpDevice();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/devices/" + device + "/transport")
                .contentType(MediaType.APPLICATION_JSON).content("{\"transport\":\"HTTP_WEBHOOK\"}")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ P5-026: the same contractVersion on MQTT and HTTP

    @Test
    void p5_026_aNewProducersContractVersionIsAcceptedOnMqttAndHttpAlikeAndStoresTheSameRecord() throws Exception {
        UUID http = httpDevice();
        String key = key(http);
        UUID mqtt = device(TransportKind.MQTT_JSON);
        String extra = ",\"contractVersion\":\"1.0\",\"messageId\":\"same-record\"";
        assertEquals(200, statusOf(TELEMETRY, http, key, sample(http, extra)));
        MqttClient publisher = new MqttClient("tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883), "p54-pub-" + UUID.randomUUID(), new MemoryPersistence());
        clients.add(publisher);
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        publisher.connect(options);
        MqttMessage message = new MqttMessage(sample(mqtt, extra).getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        publisher.publish("nexus/devices/" + mqtt + "/telemetry", message);
        await(() -> count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", mqtt) == 1, "the MQTT sample to be stored");
        String sql = "SELECT sensor_type, value, unit, timestamp FROM telemetry_history WHERE device_id = ?";
        var fromHttp = jdbc.queryForMap(sql, http);
        var fromMqtt = jdbc.queryForMap(sql, mqtt);
        assertEquals(fromHttp, fromMqtt, "the same stored record over both transports");
        assertEquals("HTTP", jdbc.queryForObject("SELECT source FROM inbound_message_dedupe WHERE device_id = ?", String.class, http));
        assertEquals("MQTT", jdbc.queryForObject("SELECT source FROM inbound_message_dedupe WHERE device_id = ?", String.class, mqtt));
    }

    @Test
    void p5_091_aMessageTheSdkRedeliversIsStoredOnceByTheBackend() throws Exception {
        UUID device = httpDevice();
        String key = key(device);
        String sdk = com.nexus.contract.PythonOracle.repositoryRoot().resolve("sdk/python").toString().replace('\\', '/');
        String program = """
                import sys, uuid
                sys.path.insert(0, '%s')
                from nexus_device_sdk import DeviceClient, InMemoryTransport
                t = InMemoryTransport()
                c = DeviceClient('%s', t, clock=lambda: 1767225600, new_id=lambda: uuid.UUID(int=7))
                c.publish_telemetry('TEMPERATURE_SENSOR', 24.5, 'CELSIUS')
                c.redeliver_last()
                c.redeliver_last()
                for s in t.sent:
                    print(s.payload.decode())
                """.formatted(sdk, device);
        String[] sent = com.nexus.contract.PythonOracle.run(program).strip().split("\\R");
        assertEquals(3, sent.length);
        assertEquals(1, new java.util.HashSet<>(List.of(sent)).size(), "the redeliveries are byte-identical");
        assertTrue(sent[0].contains("\"contractVersion\":\"1.0\"") && sent[0].contains("\"messageId\":\"00000000-0000-0000-0000-000000000007\""));
        for (String body : sent) assertEquals(200, statusOf(TELEMETRY, device, key, body), "the SDK's bytes pass the strict HTTP decoder and the core");
        assertEquals(1, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device), "one stored row for one logical message");
        assertEquals(1, count("SELECT COUNT(*) FROM inbound_message_dedupe WHERE device_id = ?", device));
    }

    /** Builds the window and the eligibility rule the way Spring does, over the test's JdbcTemplate (their constructors are package-private on purpose). */
    static final class HttpContactWindowProbe {
        private final JdbcTemplate jdbc;

        HttpContactWindowProbe(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        HttpContactWindow window() throws Exception {
            var constructor = HttpContactWindow.class.getDeclaredConstructor(JdbcTemplate.class, Duration.class);
            constructor.setAccessible(true);
            return constructor.newInstance(jdbc, Duration.ofSeconds(60));
        }

        HttpTransportEligibility eligibility(boolean deliverySupported) throws Exception {
            var constructor = HttpTransportEligibility.class.getDeclaredConstructor(HttpContactWindow.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(window(), deliverySupported);
        }
    }
}
