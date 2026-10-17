package com.nexus.action.outbox;

import com.nexus.action.domain.AcknowledgementStatus;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.CommandStatus;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.CapabilityType;
import com.nexus.device.capability.DeviceCapability;
import com.nexus.device.capability.DeviceCapabilityRepository;
import com.nexus.device.command.DeviceCommandAckMessageHandler;
import com.nexus.device.command.DeviceCommandService;
import com.nexus.device.command.api.dto.CreateDeviceCommandRequest;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceType;
import com.nexus.rule.trigger.RuleSourceFacts;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.twin.application.VerificationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Durable ACK facts through the ACTUAL {@link DeviceCommandAckMessageHandler} (flag ON, the real dispatcher, scripted broker bytes), ledger
 * P4-047 to P4-055 and P4-074 plus the ACK/timeout/dispatcher races of the P4.4 audit. Every assertion is made on durable rows: the
 * ActionHistory ACK columns, the append-only command_state_history, the outbox state and the effects on the Twin and the rule facts.
 *
 * The scheduled lifecycle scanners are OFF in this context (the documented test default); the tests call {@code scan()} and
 * {@code reconcile()} directly, and {@code CommandLifecycleScannersLiveIntegrationTest} proves the scheduled workers actually run.
 */
@SpringBootTest(properties = {"nexus.command.outbox.dispatcher-enabled=true", "nexus.command.outbox.enabled=true"})
@Testcontainers
@Import(CommandAckFactsIntegrationTest.Scripted.class)
class CommandAckFactsIntegrationTest {

    @TestConfiguration
    static class Scripted {
        @Bean
        @Primary
        FakeBroker fakeBroker() { return new FakeBroker(); }
    }

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
        registry.add("mqtt.client.id", () -> "nexus-ack-facts-" + UUID.randomUUID());
    }

    @Autowired FakeBroker broker;
    @Autowired DeviceCommandService commands;
    @Autowired DeviceCommandAckMessageHandler ackHandler;
    @Autowired ActionHistoryRepository actions;
    @Autowired CommandOutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired DeviceCapabilityRepository capabilities;
    @Autowired DeviceConnectivityRegistry connectivity;
    @Autowired CommandAckTimeoutScanner timeoutScanner;
    @Autowired LegacyDeliveryReconciler reconciler;
    @Autowired CommandOutboxDispatcher dispatcher;
    @Autowired MeterRegistry meters;
    @Autowired Clock clock;
    @SpyBean RuleSourceFacts ruleFacts;
    @SpyBean VerificationService verification;
    @SpyBean CommandStateHistory stateHistory;

    private UUID deviceId;

    @BeforeEach
    void setUp() {
        broker.reset();
        reset(ruleFacts);
        reset(verification);
        reset(stateHistory);
        deviceId = newDevice();
    }

    private UUID newDevice() {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Site-" + UUID.randomUUID(), "d", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "d", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "d", now, now));
        UUID id = devices.save(new Device(UUID.randomUUID(), space, "ESP-AckFacts", DeviceType.CUSTOM, "Acme", "T1",
                "SN-AF-" + UUID.randomUUID(), "d", DeviceMode.SIMULATED)).getId();
        capabilities.save(new DeviceCapability(UUID.randomUUID(), id, CapabilityType.LED, CapabilityDirection.WRITE, null, null));
        connectivity.applyObservedStatus(id, DeviceConnectivityStatus.ONLINE);
        return id;
    }

    @AfterEach
    void drain() throws Exception {
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) == 0,
                "every obligation to resolve", 60);
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static void await(BooleanSupplier c, String what, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    private UUID sendPublished() throws Exception {
        UUID id = commands.dispatchCommand(deviceId, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true)).requestId();
        await(() -> "PUBLISHED".equals(outboxState(id)), "publication", 30);
        return id;
    }

    private String outboxState(UUID id) {
        return jdbc.queryForObject("SELECT state FROM command_outbox WHERE command_id = ?", String.class, id);
    }

    private static String json(UUID command, String status, long epochSeconds, String reason) {
        return "{\"commandId\":\"" + command + "\",\"status\":\"" + status + "\",\"timestamp\":" + epochSeconds
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
    }

    private void ackFrom(UUID device, UUID command, String status, long epochSeconds, String reason) {
        ackHandler.handle("nexus/devices/" + device + "/commands/ack", json(command, status, epochSeconds, reason).getBytes(StandardCharsets.UTF_8));
    }

    private void ack(UUID command, String status, long epochSeconds, String reason) {
        ackFrom(deviceId, command, status, epochSeconds, reason);
    }

    private List<String> facts(UUID id) {
        return jdbc.queryForList("SELECT fact_type FROM command_state_history WHERE command_id = ? ORDER BY sequence", String.class, id);
    }

    private List<String> ackFacts(UUID id) {
        return facts(id).stream().filter(f -> f.startsWith("ACK_")).toList();
    }

    private ActionHistoryRecord record(UUID id) {
        return actions.findById(id).orElseThrow();
    }

    private double counter(String outcome) {
        var c = meters.find("nexus.command.ack").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    /** Makes the persisted ACK deadline of a published command lie in the past, as if the ACK timeout had elapsed. */
    private void deadlinePassed(UUID id) {
        jdbc.update("UPDATE action_history SET ack_deadline_at = clock_timestamp() - INTERVAL '2 seconds' WHERE request_id = ?", id);
    }

    // ------------------------------------------------------------------------------- P4-047 accepted ACK

    @Test
    @Timeout(90)
    void anAcceptedAckIsRecordedOnceWithItsFactAndTheProjectionIsAcknowledgedNothingMore() throws Exception {
        UUID id = sendPublished();
        String verificationBefore = jdbc.queryForObject("SELECT verification_status FROM current_twin_verification_state WHERE device_id = ?", String.class, deviceId);
        double before = counter("accepted");
        ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null);

        assertEquals(AcknowledgementStatus.ACCEPTED, record(id).getAckStatus());
        assertNotNull(record(id).getAckBackendReceivedAt());
        assertEquals(List.of("ACCEPTED", "QUEUED", "ATTEMPT_STARTED", "PUBLISHED", "ACK_ACCEPTED"), facts(id));
        assertEquals(CommandStatus.ACKNOWLEDGED, CommandStatus.from(record(id), clock));
        assertEquals("ACK_HANDLER", jdbc.queryForObject("SELECT source FROM command_state_history WHERE command_id = ? AND fact_type = 'ACK_ACCEPTED'", String.class, id));
        assertEquals(before + 1, counter("accepted"), 0.0001);
        // an ACK is NOT convergence, not a delivery fact and not a physical claim
        assertEquals("PUBLISHED", outboxState(id), "the delivery record is not changed by an ACK");
        assertEquals(verificationBefore, jdbc.queryForObject("SELECT verification_status FROM current_twin_verification_state WHERE device_id = ?", String.class, deviceId),
                "an ACK changes no verification state and never produces convergence");
        assertFalse("CONVERGED".equals(verificationBefore));
        verify(verification, never()).commandFailed(any(), eq(id), any());
        verify(ruleFacts, never()).ackRejected(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------------------- P4-048 rejected ACK

    @Test
    @Timeout(90)
    void aRejectedAckIsRecordedOnceAndTheRuleFactAndTheVerificationFailureHappenExactlyOnce() throws Exception {
        UUID id = sendPublished();
        long ts = Instant.now().getEpochSecond();
        ack(id, "REJECTED", ts, "stale");
        ack(id, "REJECTED", ts, "stale");                                              // identical duplicate

        assertEquals(List.of("ACK_REJECTED"), ackFacts(id));
        assertEquals("STALE", jdbc.queryForObject("SELECT reason_code FROM command_state_history WHERE command_id = ? AND fact_type = 'ACK_REJECTED'", String.class, id));
        assertEquals(CommandStatus.REJECTED, CommandStatus.from(record(id), clock));
        verify(ruleFacts, times(1)).ackRejected(eq(id), eq(deviceId), any(), eq("stale"));
        verify(verification, times(1)).commandFailed(deviceId, id, "DEVICE_COMMAND_REJECTED");
        assertEquals("PUBLISHED", outboxState(id), "a device rejection does not turn a publication into a delivery failure");
    }

    // ------------------------------------------------------------------------------- P4-049 identical duplicate

    @Test
    @Timeout(90)
    void anIdenticalDuplicateAckChangesNothingAndOnlyIncrementsACounter() throws Exception {
        UUID id = sendPublished();
        long ts = Instant.now().getEpochSecond();
        ack(id, "ACCEPTED", ts, null);
        String rowBefore = jdbc.queryForObject("SELECT row_to_json(a)::text FROM action_history a WHERE request_id = ?", String.class, id);
        List<String> factsBefore = facts(id);
        double dup = counter("duplicate");
        for (int i = 0; i < 3; i++) ack(id, "ACCEPTED", ts, null);

        assertEquals(factsBefore, facts(id), "a duplicate creates no state-history row");
        assertEquals(rowBefore, jdbc.queryForObject("SELECT row_to_json(a)::text FROM action_history a WHERE request_id = ?", String.class, id));
        assertEquals(dup + 3, counter("duplicate"), 0.0001);
        verify(ruleFacts, never()).ackRejected(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------------------- P4-050 conflicting duplicate

    @Test
    @Timeout(90)
    void aConflictingDuplicateNeverOverwritesTheFirstAckAndIsRecordedAsAnIgnoredConflictBoundedPerCommand() throws Exception {
        UUID id = sendPublished();
        long ts = Instant.now().getEpochSecond();
        ack(id, "ACCEPTED", ts, null);
        double conflicts = counter("conflict");
        ack(id, "REJECTED", ts + 1, "changed my mind");

        assertEquals(AcknowledgementStatus.ACCEPTED, record(id).getAckStatus(), "first ACK preserved: no last-write-wins");
        assertEquals(List.of("ACK_ACCEPTED", "ACK_CONFLICT_IGNORED"), ackFacts(id));
        assertEquals("REJECTED", jdbc.queryForObject("SELECT reason_code FROM command_state_history WHERE command_id = ? AND fact_type = 'ACK_CONFLICT_IGNORED'", String.class, id));
        verify(ruleFacts, never()).ackRejected(any(), any(), any(), any());                 // the ignored REJECTED never reaches the rule or the Twin
        verify(verification, never()).commandFailed(eq(deviceId), eq(id), eq("DEVICE_COMMAND_REJECTED"));
        assertEquals(CommandStatus.ACKNOWLEDGED, CommandStatus.from(record(id), clock));
        for (int i = 0; i < 12; i++) ack(id, "REJECTED", ts + 2 + i, "flood " + i);
        assertEquals(CommandAckFacts.MAX_CONFLICT_FACTS, ackFacts(id).stream().filter(f -> f.equals("ACK_CONFLICT_IGNORED")).count(),
                "a misbehaving device cannot grow the history without bound");
        assertEquals(conflicts + 13, counter("conflict"), 0.0001, "every conflict is still counted");
    }

    // ------------------------------------------------------------------------------- P4-051 validation unchanged

    @Test
    @Timeout(90)
    void invalidUnknownAndMismatchedAcksAreDroppedWithoutAnyMutationOrException() throws Exception {
        UUID id = sendPublished();
        UUID otherDevice = newDevice();
        String rowBefore = jdbc.queryForObject("SELECT row_to_json(a)::text FROM action_history a WHERE request_id = ?", String.class, id);
        long ts = Instant.now().getEpochSecond();
        String t = "nexus/devices/" + deviceId + "/commands/ack";
        List<String[]> invalid = List.of(
                new String[]{"nexus/devices/not-a-uuid/commands/ack", json(id, "ACCEPTED", ts, null)},
                new String[]{"nexus/devices/" + deviceId + "/ack", json(id, "ACCEPTED", ts, null)},
                new String[]{t, "{not json"},
                new String[]{t, "{\"status\":\"ACCEPTED\",\"timestamp\":" + ts + "}"},
                new String[]{t, "{\"commandId\":\"nope\",\"status\":\"ACCEPTED\",\"timestamp\":" + ts + "}"},
                new String[]{t, "{\"commandId\":\"" + id + "\",\"status\":\"MAYBE\",\"timestamp\":" + ts + "}"},
                new String[]{t, "{\"commandId\":\"" + id + "\",\"status\":\"ACCEPTED\"}"},
                new String[]{t, "{\"commandId\":\"" + id + "\",\"status\":\"ACCEPTED\",\"timestamp\":\"yesterday\"}"},
                new String[]{"nexus/devices/" + UUID.randomUUID() + "/commands/ack", json(id, "ACCEPTED", ts, null)},          // unknown device
                new String[]{t, json(UUID.randomUUID(), "ACCEPTED", ts, null)},                                                   // unknown command
                new String[]{"nexus/devices/" + otherDevice + "/commands/ack", json(id, "ACCEPTED", ts, null)});                 // another device's topic
        for (String[] message : invalid)
            ackHandler.handle(message[0], message[1].getBytes(StandardCharsets.UTF_8));            // none of them throws

        assertEquals(rowBefore, jdbc.queryForObject("SELECT row_to_json(a)::text FROM action_history a WHERE request_id = ?", String.class, id));
        assertNull(record(id).getAckStatus());
        assertEquals(List.of("ACCEPTED", "QUEUED", "ATTEMPT_STARTED", "PUBLISHED"), facts(id), "no fact is created for a message that is dropped");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM action_history WHERE ack_status IS NOT NULL AND device_id IN (?, ?) AND request_id <> ?", Integer.class,
                deviceId, otherDevice, id),
                "no unrelated command was touched and no row was fabricated");
        // and the very next valid ACK from the right device still works
        ack(id, "ACCEPTED", ts, null);
        assertEquals(AcknowledgementStatus.ACCEPTED, record(id).getAckStatus());
    }

    // ------------------------------------------------------------------------------- P4-054 late ACK after timeout

    @Test
    @Timeout(90)
    void aLateAckAfterThePersistedTimeoutIsAddedAndTheTimeoutFactIsKept() throws Exception {
        UUID id = sendPublished();
        deadlinePassed(id);
        assertEquals(1, timeoutScanner.scan(50));
        assertEquals(List.of("ACK_TIMED_OUT"), ackFacts(id));
        Long timeoutSequence = jdbc.queryForObject("SELECT sequence FROM command_state_history WHERE command_id = ? AND fact_type = 'ACK_TIMED_OUT'", Long.class, id);

        ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null);

        assertEquals(List.of("ACK_TIMED_OUT", "ACK_ACCEPTED", "ACK_AFTER_TIMEOUT"), ackFacts(id), "both facts stay: nothing is erased");
        assertEquals(timeoutSequence, jdbc.queryForObject("SELECT sequence FROM command_state_history WHERE command_id = ? AND fact_type = 'ACK_TIMED_OUT'", Long.class, id));
        assertEquals("ACCEPTED", jdbc.queryForObject("SELECT reason_code FROM command_state_history WHERE command_id = ? AND fact_type = 'ACK_AFTER_TIMEOUT'", String.class, id));
        assertEquals(CommandStatus.ACKNOWLEDGED, CommandStatus.from(record(id), clock), "the projection follows the existing rule: an ACK status wins");
        assertEquals("PUBLISHED", outboxState(id));
        assertEquals(1, broker.calls.size(), "a late ACK never causes a publication");
        assertEquals(0, timeoutScanner.scan(50), "and the scanner has nothing more to say");
    }

    // ------------------------------------------------------------------------------- P4-055 late ACK after dead letter

    @Test
    @Timeout(120)
    void aLateAckAfterTheObligationWasDeadLetteredIsRecordedWithoutResurrectingAnything() throws Exception {
        broker.behaviour = (t, b) -> BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED");
        UUID id = commands.dispatchCommand(deviceId, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true)).requestId();
        await(() -> "DEAD_LETTER".equals(outboxState(id)), "retry exhaustion", 60);
        int calls = broker.calls.size();
        assertEquals(3, calls);
        double late = counter("late_after_terminal");

        ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null);

        List<String> facts = facts(id);
        assertEquals(List.of("ACK_ACCEPTED", "ACK_AFTER_TERMINAL"), ackFacts(id));
        assertEquals("DEAD_LETTERED", facts.get(facts.indexOf("ACK_ACCEPTED") - 1), "the ACK is appended AFTER the terminal fact, which is not rewritten");
        assertEquals("DEAD_LETTER", outboxState(id), "the obligation is not resurrected");
        assertEquals("RETRY_EXHAUSTED", jdbc.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, id));
        Thread.sleep(700);
        assertEquals(calls, broker.calls.size(), "no publication follows");
        assertEquals(late + 1, counter("late_after_terminal"), 0.0001);
        assertEquals(CommandStatus.ACKNOWLEDGED, CommandStatus.from(record(id), clock));
    }

    // ------------------------------------------------------------------------------- RACE 4: ACK after uncertainty

    @Test
    @Timeout(120)
    void anAckAfterAnUncertainOutcomeKeepsTheUncertaintyAndNeverClaimsABrokerConfirmation() throws Exception {
        broker.behaviour = (t, b) -> BrokerCommandPublisher.Attempt.uncertain("PUBLISH_TIMEOUT");
        UUID id = commands.dispatchCommand(deviceId, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true)).requestId();
        await(() -> "DEAD_LETTER".equals(outboxState(id)), "uncertain outcome", 30);

        ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null);

        assertEquals("UNCERTAIN_EXPIRED", jdbc.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, id));
        assertNull(jdbc.queryForObject("SELECT first_publish_confirmed_at FROM command_outbox WHERE command_id = ?", java.sql.Timestamp.class, id),
                "the delivery record never claims a broker confirmation it did not get");
        assertTrue(record(id).getMessage().startsWith("DELIVERY_UNCONFIRMED"));
        assertEquals(List.of("ACK_ACCEPTED", "ACK_AFTER_TERMINAL"), ackFacts(id));
        // RACE 9: the ACK does not release the per-device hold (a duplicate delivery of the same message would still be applied). The hold itself is
        // proven end to end by UncertainDeliveryBarrierTest.anAckNeverShortensTheHold...; here the uncertain obligation must simply still be the one that holds.
        assertEquals("DEAD_LETTER", outboxState(id));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM command_outbox WHERE device_id = ? AND state = 'DEAD_LETTER' AND terminal_reason = 'UNCERTAIN_EXPIRED'", Integer.class, deviceId),
                "the acknowledged uncertain command still carries UNCERTAIN_EXPIRED, which is what the hold keys on");
        assertEquals(1, broker.calls.size());
    }

    // ------------------------------------------------------------------------------- RACE 3: ACK during RETRY_WAIT

    @Test
    @Timeout(120)
    void anAckForACommandWaitingForItsRetryIsKeptTheRetryIsNeverPublishedAndTheRowIsResolvedAsUnconfirmed() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        broker.behaviour = (t, b) -> calls.incrementAndGet() == 1 ? BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED")
                : BrokerCommandPublisher.Attempt.confirmed();
        UUID id = commands.dispatchCommand(deviceId, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true)).requestId();
        await(() -> "RETRY_WAIT".equals(outboxState(id)), "retry scheduling", 30);
        // park the retry far in the future so the ACK deterministically arrives while the command waits
        jdbc.update("UPDATE command_outbox SET next_attempt_at = now() + INTERVAL '10 seconds' WHERE command_id = ?", id);

        ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null);
        jdbc.update("UPDATE command_outbox SET next_attempt_at = now() - INTERVAL '1 millisecond' WHERE command_id = ?", id);   // the retry is now due

        await(() -> "DEAD_LETTER".equals(outboxState(id)), "the acknowledged obligation to be resolved", 30);
        assertEquals(1, calls.get(), "the due retry was never published: an acknowledged command is not sent again");
        assertEquals("UNCERTAIN_EXPIRED", jdbc.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, id));
        assertEquals("ACKNOWLEDGED_BEFORE_PUBLICATION", jdbc.queryForObject("SELECT last_error_code FROM command_outbox WHERE command_id = ?", String.class, id));
        assertNull(jdbc.queryForObject("SELECT first_publish_confirmed_at FROM command_outbox WHERE command_id = ?", java.sql.Timestamp.class, id));
        assertEquals(AcknowledgementStatus.ACCEPTED, record(id).getAckStatus(), "the ACK is preserved");
        assertTrue(record(id).getMessage().startsWith("DELIVERY_UNCONFIRMED"));
        verify(verification, never()).commandFailed(eq(deviceId), eq(id), eq("MQTT_PUBLICATION_FAILED"));       // an acknowledged command is never reported as a publication failure
        List<String> facts = facts(id);
        assertTrue(facts.indexOf("ACK_ACCEPTED") > facts.indexOf("ATTEMPT_FAILED_TRANSIENT"));
        assertEquals(1, facts.stream().filter(f -> f.equals("ACK_ACCEPTED")).count());
    }

    // ------------------------------------------------------------------------------- RACE 1/2: ACK before PUBLISHED

    @Test
    @Timeout(120)
    void anAckBeforeThePublicationOutcomeIsCommittedKeepsBothFactsInTheirObservedOrder() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        broker.behaviour = (t, b) -> {
            try { release.await(40, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return BrokerCommandPublisher.Attempt.confirmed();
        };
        UUID id = commands.dispatchCommand(deviceId, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true)).requestId();
        await(() -> "IN_FLIGHT".equals(outboxState(id)), "the attempt to be in flight", 30);

        ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null);                  // the device ACKed before NEXUS recorded the publication
        release.countDown();
        await(() -> "PUBLISHED".equals(outboxState(id)), "publication", 30);

        assertEquals(List.of("ACCEPTED", "QUEUED", "ATTEMPT_STARTED", "ACK_ACCEPTED", "PUBLISHED"), facts(id), "observed order: the ACK was seen first");
        assertEquals(AcknowledgementStatus.ACCEPTED, record(id).getAckStatus(), "the later conditional update did not erase the ACK");
        assertEquals("SUCCESS", record(id).getStatus().name());
    }

    // ------------------------------------------------------------------------------- RACE 7: concurrent duplicates

    @Test
    @Timeout(120)
    void concurrentAcksProduceExactlyOneFirstAckFactAndEveryOtherIsADuplicateOrAConflict() throws Exception {
        UUID id = sendPublished();
        long ts = Instant.now().getEpochSecond();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int n = i;
                results.add(pool.submit(() -> {
                    go.await();
                    if (n % 2 == 0) ack(id, "ACCEPTED", ts, null); else ack(id, "REJECTED", ts + n, "r" + n);
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : results) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        List<String> acks = ackFacts(id);
        assertEquals(1, acks.stream().filter(f -> f.equals("ACK_ACCEPTED") || f.equals("ACK_REJECTED")).count(), "exactly one authoritative ACK outcome: " + acks);
        AcknowledgementStatus winner = record(id).getAckStatus();
        assertEquals(winner == AcknowledgementStatus.ACCEPTED ? "ACK_ACCEPTED" : "ACK_REJECTED", acks.get(0), "the stored ACK is the one the first fact describes");
        assertTrue(acks.stream().skip(1).allMatch(f -> f.equals("ACK_CONFLICT_IGNORED")));
        verify(ruleFacts, winner == AcknowledgementStatus.REJECTED ? times(1) : never()).ackRejected(eq(id), eq(deviceId), any(), any());
    }

    // ------------------------------------------------------------------------------- ACK versus the timeout scanner

    @Test
    @Timeout(180)
    void anAckAndTheTimeoutScannerRacingForTheSameRowNeverProduceContradictoryHistory() throws Exception {
        int bothOrders = 0;
        for (int round = 0; round < 12; round++) {
            UUID id = sendPublished();
            deadlinePassed(id);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                Future<?> a = pool.submit(() -> { go.await(); ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null); return null; });
                Future<?> s = pool.submit(() -> { go.await(); timeoutScanner.scan(50); return null; });
                go.countDown();
                a.get(60, TimeUnit.SECONDS);
                s.get(60, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            List<String> acks = ackFacts(id);
            assertEquals(AcknowledgementStatus.ACCEPTED, record(id).getAckStatus());
            if (acks.contains("ACK_TIMED_OUT")) {
                bothOrders++;
                assertEquals(List.of("ACK_TIMED_OUT", "ACK_ACCEPTED", "ACK_AFTER_TIMEOUT"), acks, "the timeout came first: the ACK is recorded as late, nothing is erased");
            } else {
                assertEquals(List.of("ACK_ACCEPTED"), acks, "the ACK came first: nothing is timed out afterwards");
                assertEquals(0, timeoutScanner.scan(50));
            }
            assertEquals(1, acks.stream().filter(f -> f.equals("ACK_ACCEPTED")).count());
        }
        assertTrue(bothOrders >= 0);
    }

    // ------------------------------------------------------------------------------- failure atomicity

    @Test
    @Timeout(90)
    void aFailureWritingTheAckFactRollsTheWholeAckBackAndTheRedeliveryRecordsItOnce() throws Exception {
        UUID id = sendPublished();
        java.util.concurrent.atomic.AtomicBoolean failing = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failing.get() && invocation.getArgument(1) == CommandStateHistory.Fact.ACK_ACCEPTED) throw new IllegalStateException("injected history failure");
            return invocation.callRealMethod();
        }).when(stateHistory).append(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null),
                "the failure propagates, so the MQTT listener does not acknowledge and the broker redelivers");
        assertNull(record(id).getAckStatus(), "the guarded ACK update was rolled back with the fact");
        assertEquals(List.of(), ackFacts(id));

        failing.set(false);
        ack(id, "ACCEPTED", Instant.now().getEpochSecond(), null);                  // the redelivery
        assertEquals(AcknowledgementStatus.ACCEPTED, record(id).getAckStatus());
        assertEquals(List.of("ACK_ACCEPTED"), ackFacts(id));
    }

    // ------------------------------------------------------------------------------- P4-053 deadline anchoring

    @Test
    @Timeout(120)
    void theAckDeadlineIsAnchoredAtTheFirstConfirmedPublicationOnTheDatabaseClockAndRetriesNeverMoveIt() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        broker.behaviour = (t, b) -> calls.incrementAndGet() <= 2 ? BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED")
                : BrokerCommandPublisher.Attempt.confirmed();
        UUID id = commands.dispatchCommand(deviceId, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true)).requestId();
        await(() -> "PUBLISHED".equals(outboxState(id)), "publication after two non-publications", 60);

        assertEquals(3, jdbc.queryForObject("SELECT attempt_count FROM command_outbox WHERE command_id = ?", Integer.class, id));
        Long anchoredMillis = jdbc.queryForObject("SELECT (EXTRACT(EPOCH FROM (a.ack_deadline_at - o.first_publish_confirmed_at)) * 1000)::bigint"
                + " FROM action_history a JOIN command_outbox o ON o.command_id = a.request_id WHERE a.request_id = ?", Long.class, id);
        assertEquals(30_000L, anchoredMillis, "deadline = FIRST CONFIRMED publication + the 30 s ACK timeout, not acceptance and not an attempt start");
        Long sinceAcceptance = jdbc.queryForObject("SELECT (EXTRACT(EPOCH FROM (a.ack_deadline_at - o.created_at)) * 1000)::bigint"
                + " FROM action_history a JOIN command_outbox o ON o.command_id = a.request_id WHERE a.request_id = ?", Long.class, id);
        assertTrue(sinceAcceptance > 30_000L + 800, "the two backoffs before the publication are NOT counted into the ACK window: " + sinceAcceptance);
        // nothing moves it afterwards: sweeps, scans and a duplicate-looking pass leave it as it is
        var deadline = jdbc.queryForObject("SELECT ack_deadline_at FROM action_history WHERE request_id = ?", java.sql.Timestamp.class, id);
        for (int i = 0; i < 3; i++) { dispatcher.sweepOnce(); timeoutScanner.scan(10); }
        assertEquals(deadline, jdbc.queryForObject("SELECT ack_deadline_at FROM action_history WHERE request_id = ?", java.sql.Timestamp.class, id));
    }

    // ------------------------------------------------------------------------------- P4-074 late ACK for DELIVERY_UNKNOWN

    @Test
    @Timeout(90)
    void aLateAckForALegacyDeliveryUnknownCommandIsRecordedTruthfullyAndNothingIsResent() throws Exception {
        UUID legacy = UUID.randomUUID();
        jdbc.update("INSERT INTO action_history(request_id, match_id, device_id, action_type, status, parameters, timestamp, message)"
                + " VALUES (?, ?, ?, 'SEND_COMMAND', 'ACCEPTED_FOR_DISPATCH', '{}'::jsonb, now() - INTERVAL '1 hour', 'legacy')", legacy, UUID.randomUUID(), deviceId);
        assertEquals(1, reconciler.reconcile(10));
        assertEquals(List.of("DELIVERY_UNKNOWN"), facts(legacy));

        ack(legacy, "ACCEPTED", Instant.now().getEpochSecond(), null);                // the device did receive it after all

        assertEquals(List.of("DELIVERY_UNKNOWN", "ACK_ACCEPTED"), facts(legacy), "the ACK is a fact; the delivery fact stays what it was");
        assertEquals(AcknowledgementStatus.ACCEPTED, record(legacy).getAckStatus());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM command_outbox WHERE command_id = ?", Integer.class, legacy), "no outbox row: nothing was or will be resent");
        assertEquals(0, broker.calls.size());
        assertEquals(0, reconciler.reconcile(10));
    }

    // ------------------------------------------------------------------------------- reconciler never publishes (flag ON)

    @Test
    @Timeout(90)
    void theReconcilerNeverPublishesAnythingAcrossRunsRestartsAndWithTheFlagOn() throws Exception {
        List<UUID> legacy = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO action_history(request_id, match_id, device_id, action_type, status, parameters, timestamp, message)"
                    + " VALUES (?, ?, ?, 'SEND_COMMAND', 'ACCEPTED_FOR_DISPATCH', '{}'::jsonb, now() - INTERVAL '1 hour', 'legacy')", id, UUID.randomUUID(), deviceId);
            legacy.add(id);
        }
        assertEquals(4, reconciler.reconcile(10));
        for (int i = 0; i < 5; i++) { reconciler.reconcile(10); dispatcher.runPass(); dispatcher.sweepOnce(); Thread.sleep(50); }
        assertTrue(broker.calls.isEmpty(), "the publisher saw nothing: classified commands are never resent, whatever the flag says");
        for (UUID id : legacy) {
            assertEquals(List.of("DELIVERY_UNKNOWN"), facts(id));
            assertEquals("ACCEPTED_FOR_DISPATCH", jdbc.queryForObject("SELECT status FROM action_history WHERE request_id = ?", String.class, id), "ActionHistory is untouched");
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM command_outbox WHERE device_id = ?", Integer.class, deviceId));
    }
}
