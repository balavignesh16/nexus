package com.nexus.action.outbox;

import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.CommandStatus;
import com.nexus.action.domain.ExecutionStatus;
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
import com.nexus.twin.application.VerificationService;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The ACTUAL {@link DeviceCommandAckMessageHandler} (not an SQL imitation) against the dispatcher's outcome transactions, in the
 * orderings a real system produces: an ACK before, during and after the publish outcome is persisted, after an uncertain or
 * terminal outcome, a REJECTED ACK racing a terminal outcome, an ACK racing the acceptance of another command, and a stale
 * worker completing after the ACK.
 *
 * Invariants asserted throughout: the ACK is never erased or overwritten, a stale or later outcome never overwrites a newer
 * fact, an uncertain outcome emits no definitive failure signal, a delivery outcome writes no ACK fact, and no operation
 * deadlocks or fails.
 */
@SpringBootTest(properties = {"nexus.command.outbox.dispatcher-enabled=true", "nexus.command.outbox.enabled=true"})
@Testcontainers
@Import(CommandOutboxAckHandlerIntegrationTest.Scripted.class)
class CommandOutboxAckHandlerIntegrationTest {

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
        registry.add("mqtt.client.id", () -> "nexus-outbox-ack-" + UUID.randomUUID());
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
    @Autowired Clock clock;
    @SpyBean RuleSourceFacts ruleFacts;
    @SpyBean VerificationService verification;

    private UUID deviceId;
    private volatile CountDownLatch release;

    @BeforeEach
    void setUp() {
        release = new CountDownLatch(1);
        broker.reset();
        reset(ruleFacts);
        reset(verification);
        deviceId = newDevice();
    }

    /** A fresh device: an uncertain outcome holds its device, so each round of a loop needs its own. */
    private UUID newDevice() {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Site-" + UUID.randomUUID(), "d", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "d", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "d", now, now));
        UUID id = devices.save(new Device(UUID.randomUUID(), space, "ESP-Ack", DeviceType.CUSTOM, "Acme", "T1",
                "SN-ACK-" + UUID.randomUUID(), "d", DeviceMode.SIMULATED)).getId();
        capabilities.save(new DeviceCapability(UUID.randomUUID(), id, CapabilityType.LED, CapabilityDirection.WRITE, null, null));
        connectivity.applyObservedStatus(id, DeviceConnectivityStatus.ONLINE);
        return id;
    }

    @AfterEach
    void drain() throws Exception {
        release.countDown();
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) == 0,
                "every obligation to resolve (queued commands behind a hold expire)", 60);
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static void await(BooleanSupplier c, String what, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    /** The broker confirms (or reports the given result) only after the test releases the latch, so the attempt stays IN_FLIGHT. */
    private void publisherReturnsAfterRelease(BrokerCommandPublisher.Attempt result) {
        CountDownLatch gate = release;
        broker.behaviour = (topic, bytes) -> {
            try { gate.await(40, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return result;
        };
    }

    private UUID send(boolean value) {
        return commands.dispatchCommand(deviceId, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", value)).requestId();
    }

    private String outboxState(UUID id) {
        return jdbc.queryForObject("SELECT state FROM command_outbox WHERE command_id = ?", String.class, id);
    }

    private void ack(UUID command, String status, String reason) {
        String json = "{\"commandId\":\"" + command + "\",\"status\":\"" + status + "\",\"timestamp\":" + Instant.now().getEpochSecond()
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
        ackHandler.handle("nexus/devices/" + deviceId + "/commands/ack", json.getBytes(StandardCharsets.UTF_8));
    }

    private ActionHistoryRecord record(UUID id) {
        return actions.findById(id).orElseThrow();
    }

    private List<String> facts(UUID id) {
        return jdbc.queryForList("SELECT fact_type FROM command_state_history WHERE command_id = ? ORDER BY sequence", String.class, id);
    }

    /** How many times the dispatcher-side reaction told the twin that publication FAILED for this command. */
    private void assertTwinNotToldPublicationFailed(UUID id) {
        verify(verification, never()).commandFailed(any(), org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.startsWith("MQTT_"));
    }

    // ----------------------------------------------------------------------------------------------- 1. before

    @Test
    @Timeout(90)
    void anAckWhileTheAttemptIsInFlightSurvivesThePublishOutcomeAndNothingIsOverwritten() throws Exception {
        publisherReturnsAfterRelease(BrokerCommandPublisher.Attempt.confirmed());
        UUID id = send(true);
        await(() -> "IN_FLIGHT".equals(outboxState(id)), "the attempt to be in flight", 20);
        ack(id, "ACCEPTED", null);
        assertNotNull(record(id).getAckStatus(), "the ACK is stored while the publish outcome is still unknown");
        assertEquals(ExecutionStatus.ACCEPTED_FOR_DISPATCH, record(id).getStatus());
        Instant ackAt = record(id).getAckReceivedAt();

        release.countDown();
        await(() -> "PUBLISHED".equals(outboxState(id)), "the publish outcome", 20);
        ActionHistoryRecord after = record(id);
        assertEquals(ExecutionStatus.SUCCESS, after.getStatus(), "the outcome is recorded");
        assertEquals("ACCEPTED", after.getAckStatus().name(), "and the earlier ACK is preserved");
        assertEquals(ackAt, after.getAckReceivedAt());
        assertEquals(CommandStatus.ACKNOWLEDGED, CommandStatus.from(after, clock));
        assertEquals(List.of("ACCEPTED", "QUEUED", "ATTEMPT_STARTED", "PUBLISHED"), facts(id), "the delivery history has no ACK fact (those are P4.4)");
        assertTwinNotToldPublicationFailed(id);
    }

    // ----------------------------------------------------------------------------------------------- 2. during

    @Test
    @Timeout(180)
    void anAckRacingTheOutcomeTransactionNeverLosesEitherWrite() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 15; round++) {
                release = new CountDownLatch(1);
                publisherReturnsAfterRelease(BrokerCommandPublisher.Attempt.confirmed());
                UUID id = send(round % 2 == 0);
                await(() -> "IN_FLIGHT".equals(outboxState(id)), "round in flight", 20);
                CyclicBarrier barrier = new CyclicBarrier(2);
                CountDownLatch gate = release;
                Future<?> a = pool.submit(() -> { barrier.await(); gate.countDown(); return null; });
                Future<?> b = pool.submit(() -> { barrier.await(); ack(id, "ACCEPTED", null); return null; });
                a.get(30, TimeUnit.SECONDS);
                b.get(30, TimeUnit.SECONDS);
                await(() -> "PUBLISHED".equals(outboxState(id)), "round published", 20);
                ActionHistoryRecord r = record(id);
                assertEquals(ExecutionStatus.SUCCESS, r.getStatus(), "round " + round + ": the publish outcome is not lost");
                assertNotNull(r.getAckStatus(), "round " + round + ": the ACK is not lost");
                assertNotNull(r.getPublishedAt());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ----------------------------------------------------------------------------------------------- 3. after

    @Test
    @Timeout(90)
    void anAckAfterAnUncertainOutcomeIsStoredWithoutTouchingTheDeliveryRecordOrAnyFailureSignal() throws Exception {
        publisherReturnsAfterRelease(BrokerCommandPublisher.Attempt.uncertain("PUBLISH_TIMEOUT"));
        UUID id = send(true);
        await(() -> "IN_FLIGHT".equals(outboxState(id)), "in flight", 20);
        release.countDown();
        await(() -> "DEAD_LETTER".equals(outboxState(id)), "the uncertain outcome", 20);
        List<String> factsBefore = facts(id);
        assertEquals("UNCERTAIN_EXPIRED", jdbc.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, id));
        assertTwinNotToldPublicationFailed(id);   // an uncertain outcome does not mark the twin verification as failed

        ack(id, "ACCEPTED", null);                                   // the message did reach the device after all

        ActionHistoryRecord r = record(id);
        assertEquals("ACCEPTED", r.getAckStatus().name());
        assertEquals(CommandStatus.ACKNOWLEDGED, CommandStatus.from(r, clock), "the ACK wins in the status projection");
        assertTrue(r.getMessage().startsWith("DELIVERY_UNCONFIRMED"), "the uncertainty stays recorded");
        assertEquals("DEAD_LETTER", outboxState(id), "the delivery record is untouched");
        assertEquals(factsBefore, facts(id), "no history fact is written by a late ACK");
        verify(ruleFacts, never()).publicationFailed(any(), any(), any(), any());
        assertTwinNotToldPublicationFailed(id);
    }

    @Test
    @Timeout(90)
    void anAckAfterTheCommandExpiredUnsentIsStoredAndNothingElseChanges() throws Exception {
        // an expired-unsent command: accepted, then its window passes before any attempt (inserted directly so no race with the dispatcher)
        UUID expired = UUID.randomUUID();
        try (var c = jdbc.getDataSource().getConnection()) {
            Instant old = Instant.now().minusSeconds(120);
            UUID command = OutboxSql.action(c, deviceId, "ACCEPTED_FOR_DISPATCH", old);
            OutboxSql.insert(c, OutboxSql.queued(command, deviceId, old));
            expired = command;
        }
        UUID id = expired;
        await(() -> "DEAD_LETTER".equals(outboxState(id)), "the sweep to expire it", 20);
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, id));
        List<String> factsBefore = facts(id);
        ack(id, "ACCEPTED", null);
        assertEquals("ACCEPTED", record(id).getAckStatus().name());
        assertEquals(ExecutionStatus.FAILURE, record(id).getStatus());
        assertEquals(factsBefore, facts(id));
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, id));
    }

    // ----------------------------------------------------------------------------------------------- 4. rejected

    @Test
    @Timeout(180)
    void aRejectedAckRacingAnUncertainOutcomeProducesExactlyOneFailureSignal() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                release = new CountDownLatch(1);
                deviceId = newDevice();
                publisherReturnsAfterRelease(BrokerCommandPublisher.Attempt.uncertain("PUBLISH_TIMEOUT"));
                UUID id = send(round % 2 == 0);
                await(() -> "IN_FLIGHT".equals(outboxState(id)), "round in flight", 20);
                CyclicBarrier barrier = new CyclicBarrier(2);
                CountDownLatch gate = release;
                Future<?> a = pool.submit(() -> { barrier.await(); gate.countDown(); return null; });
                Future<?> b = pool.submit(() -> { barrier.await(); ack(id, "REJECTED", "STALE"); return null; });
                a.get(30, TimeUnit.SECONDS);
                b.get(30, TimeUnit.SECONDS);
                await(() -> "DEAD_LETTER".equals(outboxState(id)), "round dead letter", 20);
                assertEquals("REJECTED", record(id).getAckStatus().name());
                verify(verification, times(1)).commandFailed(deviceId, id, "DEVICE_COMMAND_REJECTED");   // told once, by the device's rejection
                assertTwinNotToldPublicationFailed(id);
            }
            verify(ruleFacts, never()).publicationFailed(any(), any(), any(), any());
            verify(ruleFacts, times(10)).ackRejected(any(), any(), any(), any());
        } finally {
            pool.shutdownNow();
        }
    }

    // ----------------------------------------------------------------------------------------------- 5. acceptance

    @Test
    @Timeout(90)
    void anAckRacingTheAcceptanceOfAnotherCommandNeitherDeadlocksNorLosesAnything() throws Exception {
        publisherReturnsAfterRelease(BrokerCommandPublisher.Attempt.confirmed());
        UUID first = send(true);
        await(() -> "IN_FLIGHT".equals(outboxState(first)), "first in flight", 20);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<UUID> accept = pool.submit(() -> { barrier.await(); return send(false); });
            Future<?> acknowledge = pool.submit(() -> { barrier.await(); ack(first, "ACCEPTED", null); return null; });
            UUID second = accept.get(30, TimeUnit.SECONDS);
            acknowledge.get(30, TimeUnit.SECONDS);
            assertEquals("QUEUED", outboxState(second), "the newer command waits behind the in-flight one");
            assertEquals(ExecutionStatus.ACCEPTED_FOR_DISPATCH, record(second).getStatus());
            assertNotNull(record(first).getAckStatus());
            release.countDown();
            await(() -> "PUBLISHED".equals(outboxState(first)) && "PUBLISHED".equals(outboxState(second)), "both delivered in order", 30);
            List<String> sent = broker.calls.stream().map(FakeBroker.Call::text).toList();
            assertTrue(sent.get(0).contains(first.toString()) && sent.get(1).contains(second.toString()));
        } finally {
            pool.shutdownNow();
        }
    }

    // ----------------------------------------------------------------------------------------------- 6. stale worker

    @Test
    @Timeout(120)
    void aStaleWorkerCompletingAfterTheAckAndTheLeaseRecoveryChangesNothing() throws Exception {
        publisherReturnsAfterRelease(BrokerCommandPublisher.Attempt.confirmed());
        UUID id = send(true);
        await(() -> "IN_FLIGHT".equals(outboxState(id)), "in flight", 20);
        jdbc.update("UPDATE command_outbox SET lease_expires_at = now() - INTERVAL '1 second' WHERE command_id = ?", id);   // the worker stalls past its lease
        await(() -> "DEAD_LETTER".equals(outboxState(id)), "lease recovery to resolve it as uncertain", 20);
        ack(id, "ACCEPTED", null);                                                                                        // the device did receive it
        List<String> factsBefore = facts(id);
        ActionHistoryRecord before = record(id);

        release.countDown();                                                                                              // the stalled worker now reports a confirmation
        Thread.sleep(1500);

        ActionHistoryRecord after = record(id);
        assertEquals("DEAD_LETTER", outboxState(id), "the stale confirmation did not resurrect the state");
        assertEquals(before.getStatus(), after.getStatus(), "and did not overwrite the recorded outcome");
        assertEquals(before.getMessage(), after.getMessage());
        assertNull(after.getPublishedAt(), "no misleading publication time");
        assertEquals("ACCEPTED", after.getAckStatus().name(), "the ACK is intact");
        assertEquals(factsBefore, facts(id), "no PUBLISHED fact was appended");
        verify(ruleFacts, never()).publicationFailed(any(), any(), any(), any());
        assertTwinNotToldPublicationFailed(id);
    }
}
