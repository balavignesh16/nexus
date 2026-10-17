package com.nexus.telemetry.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
import com.nexus.realtime.RealtimePublisher;
import com.nexus.rule.trigger.RuleSourceFacts;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.api.dto.TelemetryResponse;
import com.nexus.telemetry.application.InboundMessageDedupe;
import com.nexus.telemetry.application.InvalidTelemetryException;
import com.nexus.telemetry.application.TelemetryMessageConflictException;
import com.nexus.telemetry.application.TelemetryService;
import com.nexus.telemetry.application.TelemetrySource;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The telemetry inbox on the real service and a real PostgreSQL (R17, ledger P4-075 to P4-079, P4-083, P4-085, plus the atomicity of
 * P4-076): an identical redelivery stores one row, makes no second Twin update, publishes nothing and evaluates no rule again; a
 * conflicting duplicate stores nothing and is a 409; legacy messages without an id are never deduplicated; the key is per device and
 * shared by MQTT and HTTP; and a failure after the claim removes the claim. The MQTT client points at an unused port so the test can
 * never reach a real broker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TelemetryInboxIntegrationTest {

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
        registry.add("mqtt.broker.url", () -> "tcp://127.0.0.1:1");
        registry.add("mqtt.client.id", () -> "p45-inbox-" + UUID.randomUUID());
    }

    @Autowired TelemetryService telemetry;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @SpyBean RuleSourceFacts ruleFacts;
    @SpyBean RealtimePublisher realtime;
    @SpyBean InboundMessageDedupe dedupe;
    @Autowired PlatformTransactionManager transactionManager;

    private UUID device;
    private UUID otherDevice;
    private double duplicatesMqtt0, duplicatesHttp0, conflictsMqtt0;
    private static final Instant SAMPLE_TIME = Instant.parse("2026-10-17T12:00:00Z");

    @BeforeEach
    void freshDevices() {
        reset(ruleFacts, realtime, dedupe);
        duplicatesMqtt0 = counter("nexus.telemetry.duplicates", "MQTT");
        duplicatesHttp0 = counter("nexus.telemetry.duplicates", "HTTP");
        conflictsMqtt0 = counter("nexus.telemetry.dedupe.conflicts", "MQTT");
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Inbox", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "test", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "test", now, now));
        device = activeDevice(space, "A");
        otherDevice = activeDevice(space, "B");
    }

    private UUID activeDevice(Space space, String name) {
        Device d = new Device(UUID.randomUUID(), space, name, DeviceType.CUSTOM, "NEXUS", "TEST", "P45-" + UUID.randomUUID(), null, DeviceMode.SIMULATED);
        d.update(name, DeviceStatus.ACTIVE, null);
        return devices.save(d).getId();
    }

    private static TelemetryRequest temperature(UUID device, double value, String messageId) {
        return new TelemetryRequest(device, SAMPLE_TIME, "TEMPERATURE", value, "CELSIUS", null, null, messageId);
    }

    private long telemetryRows(UUID d) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", Long.class, d);
    }

    private long dedupeRows(UUID d) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inbound_message_dedupe WHERE device_id = ?", Long.class, d);
    }

    private long transitions(UUID d) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM twin_state_transitions WHERE device_id = ?", Long.class, d);
    }

    private long twins(UUID d) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM digital_twins WHERE device_id = ?", Long.class, d);
    }

    private long events(UUID d) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM domain_events WHERE device_id = ?", Long.class, d);
    }

    private double counter(String name, String source) {
        var c = meters.find(name).tag("source", source).counter();
        return c == null ? 0 : c.count();
    }

    private static void settle() throws InterruptedException {
        Thread.sleep(400); // post-commit work (realtime publication, Twin events) runs on the committing thread; allow any listener threads to finish
    }

    // ---- P4-075: an identical redelivery is processed once ----

    @Test
    void aRedeliveredMessageIdStoresOneRowAndHasNoSecondSideEffect() throws Exception {
        TelemetryResponse first = telemetry.processTelemetry(temperature(device, 21.5, "msg-1"), TelemetrySource.MQTT);
        assertNull(first.duplicate(), "a normal acceptance has no duplicate field");
        settle();
        long transitionsAfterFirst = transitions(device), eventsAfterFirst = events(device);
        assertEquals(1, telemetryRows(device));
        assertEquals(1, dedupeRows(device));
        verify(ruleFacts, times(1)).acceptedTelemetry(any(), any(), any(), anyString());
        verify(realtime, times(1)).publishTelemetry(any(), anyString());

        TelemetryResponse second = telemetry.processTelemetry(temperature(device, 21.5, "msg-1"), TelemetrySource.MQTT);
        settle();

        assertTrue(second.repeatsAcceptedMessage());
        assertEquals(first.id(), second.id(), "the original sample is reported while it is still stored");
        assertEquals(1, telemetryRows(device), "no second telemetry row");
        assertEquals(1, dedupeRows(device));
        assertEquals(transitionsAfterFirst, transitions(device), "no second Twin transition");
        assertEquals(eventsAfterFirst, events(device), "no second event");
        verify(ruleFacts, times(1)).acceptedTelemetry(any(), any(), any(), anyString());   // invariant 10: no second rule evaluation, hence no second command
        verify(realtime, times(1)).publishTelemetry(any(), anyString());
        assertEquals(1, counter("nexus.telemetry.duplicates", "MQTT") - duplicatesMqtt0);
    }

    // ---- P4-077 / invariant 3 and 4: only an id deduplicates ----

    @Test
    void messagesWithoutAnIdAreNeverDeduplicatedEvenWhenIdentical() {
        telemetry.processTelemetry(temperature(device, 21.5, null), TelemetrySource.MQTT);
        telemetry.processTelemetry(temperature(device, 21.5, null), TelemetrySource.MQTT);
        assertEquals(2, telemetryRows(device), "legacy behaviour: identical samples without an id are both stored");
        assertEquals(0, dedupeRows(device));
    }

    @Test
    void identicalReadingsWithDifferentIdsAreAcceptedIndependently() {
        telemetry.processTelemetry(temperature(device, 21.5, "a"), TelemetrySource.MQTT);
        TelemetryResponse second = telemetry.processTelemetry(temperature(device, 21.5, "b"), TelemetrySource.MQTT);
        assertNull(second.duplicate());
        assertEquals(2, telemetryRows(device));
        assertEquals(2, dedupeRows(device));
    }

    // ---- P4-078: the id is scoped by device ----

    @Test
    void theSameIdFromTwoDevicesIsStoredTwice() {
        telemetry.processTelemetry(temperature(device, 21.5, "shared"), TelemetrySource.MQTT);
        TelemetryResponse other = telemetry.processTelemetry(temperature(otherDevice, 21.5, "shared"), TelemetrySource.MQTT);
        assertNull(other.duplicate());
        assertEquals(1, telemetryRows(device));
        assertEquals(1, telemetryRows(otherDevice));
    }

    // ---- P4-079: a conflicting duplicate keeps the first ----

    @Test
    void aConflictingDuplicateStoresNothingAndKeepsTheFirstPayload() {
        telemetry.processTelemetry(temperature(device, 21.5, "msg-c"), TelemetrySource.MQTT);
        assertThrows(TelemetryMessageConflictException.class, () -> telemetry.processTelemetry(temperature(device, 99.0, "msg-c"), TelemetrySource.MQTT));
        assertEquals(1, telemetryRows(device));
        assertEquals(21.5, jdbc.queryForObject("SELECT value FROM telemetry_history WHERE device_id = ?", Double.class, device), "the first submission wins");
        assertEquals(1, dedupeRows(device));
        assertEquals(1, counter("nexus.telemetry.dedupe.conflicts", "MQTT") - conflictsMqtt0);
        // a different timestamp, unit or capability under the same id is also a conflict
        assertThrows(TelemetryMessageConflictException.class, () -> telemetry.processTelemetry(
                new TelemetryRequest(device, SAMPLE_TIME.plusSeconds(1), "TEMPERATURE", 21.5, "CELSIUS", null, null, "msg-c"), TelemetrySource.HTTP));
    }

    // ---- P4-083: HTTP uses the same semantics, and the key space is shared across transports ----

    @Test
    void httpDuplicateIsASuccessfulNoOpAndAConflictIs409AcrossTransports() throws Exception {
        String body = mapper.writeValueAsString(temperature(device, 21.5, "http-1"));
        mockMvc.perform(post("/api/v1/telemetry").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").exists()).andExpect(jsonPath("$.duplicate").doesNotExist());
        settle();
        long transitionsBefore = transitions(device);

        mockMvc.perform(post("/api/v1/telemetry").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true)).andExpect(jsonPath("$.id").exists()).andExpect(jsonPath("$.value").value(21.5));
        settle();
        assertEquals(1, telemetryRows(device));
        assertEquals(transitionsBefore, transitions(device));
        assertEquals(1, counter("nexus.telemetry.duplicates", "HTTP") - duplicatesHttp0);

        // an id first seen over HTTP is a duplicate over MQTT (one key space)
        assertTrue(telemetry.processTelemetry(temperature(device, 21.5, "http-1"), TelemetrySource.MQTT).repeatsAcceptedMessage());
        assertEquals(1, telemetryRows(device));

        mockMvc.perform(post("/api/v1/telemetry").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(temperature(device, 55.0, "http-1"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        assertEquals(1, telemetryRows(device));
    }

    @Test
    void aMessageIdThatIsNotTextOrViolatesTheFormatIsHttp400AndStoresNothing() throws Exception {
        String base = "{\"deviceId\":\"" + device + "\",\"timestamp\":\"" + SAMPLE_TIME + "\",\"sensorType\":\"TEMPERATURE\",\"value\":21.5,\"unit\":\"CELSIUS\",\"messageId\":";
        for (String bad : new String[] {"123", "true", "\"has space\"", "\"\"", "\"" + "x".repeat(65) + "\""}) {
            mockMvc.perform(post("/api/v1/telemetry").contentType(MediaType.APPLICATION_JSON).content(base + bad + "}")).andExpect(status().isBadRequest());
        }
        assertEquals(0, telemetryRows(device));
        assertEquals(0, dedupeRows(device));
    }

    @Test
    void aPayloadExactlyAsTheCommittedFirmwareSendsItIsStillAcceptedAndStoredUnchanged() throws Exception {
        String legacy = "{\"deviceId\":\"" + device + "\",\"timestamp\":\"" + SAMPLE_TIME + "\",\"sensorType\":\"TEMPERATURE\",\"value\":22.5,\"unit\":\"CELSIUS\"}";
        mockMvc.perform(post("/api/v1/telemetry").contentType(MediaType.APPLICATION_JSON).content(legacy))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.value").value(22.5)).andExpect(jsonPath("$.duplicate").doesNotExist());
        assertEquals(1, telemetryRows(device));
        assertEquals(0, dedupeRows(device), "no id, no dedupe entry");
    }

    // ---- P4-076: the claim and the ingestion commit together or not at all ----

    @Test
    void aFailureAfterTheClaimRemovesTheClaimAndEveryEffectAndTheRetryIsAcceptedNormally() throws Exception {
        doThrow(new IllegalStateException("injected failure after the telemetry write and the Twin update"))
                .when(ruleFacts).acceptedTelemetry(any(), any(), any(), anyString());
        assertThrows(IllegalStateException.class, () -> telemetry.processTelemetry(temperature(device, 21.5, "atomic-1"), TelemetrySource.MQTT));
        settle();

        assertEquals(0, dedupeRows(device), "no dedupe row survives a rolled-back ingestion");
        assertEquals(0, telemetryRows(device), "no telemetry row");
        assertEquals(0, twins(device), "no Twin row");
        assertEquals(0, transitions(device));
        assertEquals(0, events(device));
        verify(realtime, times(0)).publishTelemetry(any(), anyString());   // invariant 11: nothing escaped the rolled-back transaction

        reset(ruleFacts);
        TelemetryResponse retried = telemetry.processTelemetry(temperature(device, 21.5, "atomic-1"), TelemetrySource.MQTT);
        assertNull(retried.duplicate(), "the redelivery after the failure is the first real acceptance, not a duplicate");
        assertEquals(1, telemetryRows(device));
        assertEquals(1, dedupeRows(device));
    }

    @Test
    void aTransientInfrastructureFailureOfTheClaimPropagatesUnchangedAndLeavesNoTrace() {
        // the bean is MANDATORY-transactional, so the stubbing call itself must run inside a transaction
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                doThrow(new DataAccessResourceFailureException("database unreachable")).when(dedupe).claim(any(), anyString(), any(), anyString(), any()));
        assertThrows(DataAccessResourceFailureException.class, () -> telemetry.processTelemetry(temperature(device, 21.5, "outage"), TelemetrySource.MQTT),
                "a database failure must not be converted into a permanent-invalid exception");
        assertEquals(0, telemetryRows(device));
        assertEquals(0, dedupeRows(device));
    }

    // ---- invalid input never becomes a success record (invariant 5) and is typed (decision A1) ----

    @Test
    void deterministicallyInvalidTelemetryIsTypedAndLeavesNoDedupeEvidence() {
        // the rehearsal defect: the legacy numeric 1.0 for a BOOLEAN capability
        InvalidTelemetryException wrongType = assertThrows(InvalidTelemetryException.class,
                () -> telemetry.processTelemetry(new TelemetryRequest(device, SAMPLE_TIME, "MOTION", 1.0, "BOOLEAN", null, null, "bad-1"), TelemetrySource.MQTT));
        assertNotNull(wrongType.reason());
        InvalidTelemetryException unsupported = assertThrows(InvalidTelemetryException.class,
                () -> telemetry.processTelemetry(new TelemetryRequest(device, SAMPLE_TIME, null, 1.0, null, "no_such_capability", null, "bad-2"), TelemetrySource.MQTT));
        assertEquals("CAPABILITY_IDENTITY_INVALID", unsupported.reason());
        assertThrows(InvalidTelemetryException.class,
                () -> telemetry.processTelemetry(new TelemetryRequest(device, SAMPLE_TIME, "NOT_A_SENSOR", 1.0, "X", null, null, "bad-3"), TelemetrySource.MQTT));
        assertEquals(0, dedupeRows(device), "a rejected message is never recorded as seen");
        assertEquals(0, telemetryRows(device));
        // the same ids can still be used for a valid payload afterwards
        assertNull(telemetry.processTelemetry(new TelemetryRequest(device, SAMPLE_TIME, null, true, null, "motion", null, "bad-1"), TelemetrySource.MQTT).duplicate());
        assertEquals(1, telemetryRows(device));
    }

    // ---- P4-085: concurrent duplicates store exactly one row ----

    @Test
    void concurrentIdenticalSubmissionsCommitExactlyOnce() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<TelemetryResponse>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            TelemetrySource source = i % 2 == 0 ? TelemetrySource.MQTT : TelemetrySource.HTTP;
            results.add(pool.submit((Callable<TelemetryResponse>) () -> {
                go.await();
                return telemetry.processTelemetry(temperature(device, 21.5, "race-1"), source);
            }));
        }
        go.countDown();
        int fresh = 0, duplicates = 0;
        for (Future<TelemetryResponse> f : results) {
            TelemetryResponse r = f.get(60, TimeUnit.SECONDS);
            if (r.repeatsAcceptedMessage()) duplicates++; else fresh++;
        }
        pool.shutdownNow();
        settle();
        assertEquals(1, fresh, "exactly one submission is accepted");
        assertEquals(threads - 1, duplicates);
        assertEquals(1, telemetryRows(device));
        assertEquals(1, dedupeRows(device));
        verify(ruleFacts, times(1)).acceptedTelemetry(any(), any(), any(), anyString());
        verify(realtime, times(1)).publishTelemetry(any(), anyString());
    }

    @Test
    void concurrentSubmissionsOfOneIdWithDifferentPayloadsStoreOneAndConflictTheRest() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            double value = 20 + i;   // all different: exactly one wins
            results.add(pool.submit((Callable<Object>) () -> {
                go.await();
                try { return telemetry.processTelemetry(temperature(device, value, "race-2"), TelemetrySource.MQTT); }
                catch (TelemetryMessageConflictException e) { return e; }
            }));
        }
        go.countDown();
        int stored = 0, conflicts = 0;
        for (Future<Object> f : results) {
            Object r = f.get(60, TimeUnit.SECONDS);
            if (r instanceof TelemetryMessageConflictException) conflicts++;
            else { assertFalse(((TelemetryResponse) r).repeatsAcceptedMessage()); stored++; }
        }
        pool.shutdownNow();
        assertEquals(1, stored);
        assertEquals(threads - 1, conflicts);
        assertEquals(1, telemetryRows(device));
        assertEquals(1, dedupeRows(device));
    }

    // ---- lock ordering: concurrent DIFFERENT messages of one device must neither deadlock nor hang ----

    @Test
    void concurrentDifferentMessagesOfOneDeviceNeitherDeadlockNorHang() throws Exception {
        int threads = 6;    // below the connection pool (10): beyond it every committing sample waits for a second connection (pre-existing, unrelated)
        int rounds = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long expectedRows = 0, expectedClaims = 0;
        for (int round = 0; round < rounds; round++) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<TelemetryResponse>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = round * threads + i;
                // identified messages from both transports and messages without an id, all for the SAME device at the same instant
                String id = i % 3 == 2 ? null : "mixed-" + n;
                TelemetrySource source = i % 2 == 0 ? TelemetrySource.MQTT : TelemetrySource.HTTP;
                if (id != null) expectedClaims++;
                results.add(pool.submit((Callable<TelemetryResponse>) () -> {
                    go.await();
                    return telemetry.processTelemetry(new TelemetryRequest(device, SAMPLE_TIME.plusSeconds(n), "TEMPERATURE", 20.0 + n, "CELSIUS", null, null, id), source);
                }));
            }
            go.countDown();
            for (Future<TelemetryResponse> f : results) {
                assertNull(f.get(30, TimeUnit.SECONDS).duplicate(), "round " + round + ": every message is distinct, so none is a duplicate (a timeout means a deadlock or a hang)");
            }
            expectedRows += threads;
        }
        pool.shutdownNow();
        assertEquals(expectedRows, telemetryRows(device));
        assertEquals(expectedClaims, dedupeRows(device));
    }

    // ---- the claim never reaches past the device it belongs to ----

    @Test
    void deletingADeviceRemovesItsEntriesWithoutOrphansAndDoesNotTouchOtherDevices() {
        telemetry.processTelemetry(temperature(device, 21.5, "gone-1"), TelemetrySource.MQTT);
        telemetry.processTelemetry(temperature(otherDevice, 21.5, "kept-1"), TelemetrySource.MQTT);
        jdbc.update("DELETE FROM devices WHERE id = ?", device);
        assertEquals(0, dedupeRows(device));
        assertEquals(1, dedupeRows(otherDevice));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inbound_message_dedupe d WHERE NOT EXISTS (SELECT 1 FROM devices x WHERE x.id = d.device_id)", Long.class));
    }
}
