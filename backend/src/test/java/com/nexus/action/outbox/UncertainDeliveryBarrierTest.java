package com.nexus.action.outbox;

import com.nexus.action.outbox.OutboxDeliveryOperations.AttemptResult;
import com.nexus.action.outbox.OutboxDeliveryOperations.Claimed;
import com.nexus.action.outbox.OutboxDeliveryOperations.Held;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-device barrier after an UNCERTAIN delivery outcome (forensic finding H2).
 *
 * Why it exists: a publish whose confirmation timed out may still be delivered (the Paho client keeps the QoS 1 message and
 * resends it on reconnect; the broker may redeliver), and the committed firmware applies any command whose timestamp is within
 * 30 seconds of its own clock and has NO commandId deduplication. If the next command of the device were sent at once, the
 * late copy of the earlier one could overwrite it (A = ON delayed past B = OFF leaves the LED ON). So later commands of that
 * device are held, durably, until the uncertain command's freshness horizon (timestamp + 30 s + the 10 s clock/network
 * margin) has passed; after that the firmware rejects any copy as STALE.
 *
 * What it does NOT claim: no physical ordering guarantee beyond that, no protection against duplicates of a CONFIRMED
 * publish (that needs device-side deduplication, which would need a firmware change), and an ACK never shortens the hold.
 */
@Testcontainers
@Timeout(120)
class UncertainDeliveryBarrierTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    static OutboxTestKit kit;
    static final Duration LEASE = Duration.ofSeconds(6);
    /** Test-side model of the committed firmware: it applies a command only if |its clock - command timestamp| <= 30 s. */
    static final long FIRMWARE_FRESHNESS_SECONDS = 30;
    static final long MAX_CLOCK_ERROR_SECONDS = 5;

    @BeforeAll
    static void start() {
        OutboxTestKit.migrate(db);
        kit = new OutboxTestKit(db);
    }

    @AfterAll
    static void stop() {
        kit.close();
    }

    @BeforeEach
    void clean() {
        kit.jdbc.execute("TRUNCATE command_state_history, command_outbox");
    }

    @AfterEach
    void quiesce() throws InterruptedException {
        Thread.sleep(300);
    }

    private static boolean wouldFirmwareApply(long commandTimestamp, double realTimeSeconds, double deviceClockErrorSeconds) {
        return Math.abs((realTimeSeconds + deviceClockErrorSeconds) - commandTimestamp) <= FIRMWARE_FRESHNESS_SECONDS;
    }

    private long dbEpoch() {
        return kit.jdbc.queryForObject("SELECT floor(extract(epoch from clock_timestamp()))::bigint", Long.class);
    }

    /** A terminal row whose payload timestamp and creation are {@code ageSeconds} before the database clock. */
    private UUID terminal(UUID device, long ageSeconds, String state, String reason) {
        try (Connection c = kit.dataSource.getConnection()) {
            Instant when = Instant.ofEpochSecond(dbEpoch() - ageSeconds);
            UUID command = OutboxSql.action(c, device, state.equals("PUBLISHED") ? "SUCCESS" : "FAILURE", when);
            Map<String, Object> row = OutboxSql.queued(command, device, when);
            Map<String, Object> over = new LinkedHashMap<>();
            over.put("state", state);
            over.put("attempt_count", 1);
            over.put("next_attempt_at", null);
            over.put("terminal_at", Timestamp.from(when));
            if (state.equals("PUBLISHED")) over.put("first_publish_confirmed_at", Timestamp.from(when));
            else {
                over.put("terminal_reason", reason);
                over.put("last_error_code", "TEST");
            }
            row.putAll(over);
            OutboxSql.insert(c, row);
            return command;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(BooleanSupplier c, String what, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(50);
        }
    }

    // ------------------------------------------------------------------------------------------ the barrier

    @Test
    void aLaterCommandIsNeitherACandidateNorClaimableWhileTheUncertainCommandCouldStillReachTheDevice() {
        UUID device = kit.device();
        terminal(device, 25, "DEAD_LETTER", "UNCERTAIN_EXPIRED");          // horizon: timestamp + 40 s = about 15 s from now
        UUID b = kit.enqueueDue(device, 0);
        assertFalse(kit.ops.candidates(10).contains(b));
        assertTrue(kit.ops.claim(b, "w", LEASE).isEmpty(), "the claim itself refuses a held command");
        assertEquals("QUEUED", kit.state(b));
        assertEquals(0, kit.attempts(b));
        // another device is not affected
        UUID other = kit.enqueueDue(kit.device(), 1);
        assertTrue(kit.ops.candidates(10).contains(other));
    }

    @Test
    void theHoldEndsAtTheFreshnessHorizonPlusMarginAndNotBefore() throws Exception {
        UUID device = kit.device();
        long ageSeconds = 39;                                                // 1 s of hold left (timestamp + 40 s)
        UUID uncertain = terminal(device, ageSeconds, "DEAD_LETTER", "UNCERTAIN_EXPIRED");
        long timestamp = kit.jdbc.queryForObject("SELECT payload_epoch_seconds FROM command_outbox WHERE command_id = ?", Long.class, uncertain);
        UUID b = kit.enqueueDue(device, 0);
        assertFalse(kit.ops.candidates(10).contains(b), "still inside the horizon");
        Thread.sleep(2300);
        assertTrue(kit.ops.candidates(10).contains(b), "released once timestamp + 40 s has passed on the database clock");
        assertTrue(kit.ops.claim(b, "w", LEASE).isPresent());
        // the model of the firmware: a late copy of the uncertain command at the moment B may first be sent is already STALE
        double releaseTime = dbEpoch();
        for (double skew : new double[]{-MAX_CLOCK_ERROR_SECONDS, 0, MAX_CLOCK_ERROR_SECONDS})
            assertFalse(wouldFirmwareApply(timestamp, releaseTime, skew), "a copy delivered after the release is rejected as STALE (device clock error " + skew + ")");
    }

    @Test
    void theHoldCoversEveryClockErrorAndNetworkDelayTheFirmwareCouldSee() {
        // the latest real time the firmware could still APPLY a copy: timestamp + 30 s + the largest device clock error behind real time
        long timestamp = 1_000_000;
        double latestApply = timestamp + FIRMWARE_FRESHNESS_SECONDS + MAX_CLOCK_ERROR_SECONDS;
        assertTrue(wouldFirmwareApply(timestamp, latestApply, -MAX_CLOCK_ERROR_SECONDS));
        assertFalse(wouldFirmwareApply(timestamp, latestApply + 0.5, -MAX_CLOCK_ERROR_SECONDS));
        long hold = CommandDeliveryLimits.UNCERTAIN_DELIVERY_HOLD.toSeconds();
        assertTrue(timestamp + hold >= latestApply + 2, "the hold exceeds the latest possible application by at least the network allowance: " + hold);
        assertEquals(40, hold);
        assertEquals(60, CommandDeliveryLimits.UNCERTAIN_DELIVERY_HOLD_CAP.toSeconds());
    }

    @Test
    void anAckNeverShortensTheHoldBecauseADuplicateOfTheMessageWouldStillBeApplied() {
        UUID device = kit.device();
        UUID uncertain = terminal(device, 25, "DEAD_LETTER", "UNCERTAIN_EXPIRED");
        kit.jdbc.update("UPDATE action_history SET ack_status = 'ACCEPTED', ack_received_at = now() WHERE request_id = ?", uncertain);
        UUID b = kit.enqueueDue(device, 0);
        assertFalse(kit.ops.candidates(10).contains(b));
        assertTrue(kit.ops.claim(b, "w", LEASE).isEmpty());
    }

    @Test
    void onlyAnUncertainOutcomeHoldsTheDeviceAnExpiredNeverSentOrPublishedPredecessorDoesNot() {
        for (String[] kind : new String[][]{{"DEAD_LETTER", "EXPIRED"}, {"DEAD_LETTER", "PERMANENT_FAILURE"}, {"DEAD_LETTER", "PAYLOAD_INTEGRITY"},
                {"DEAD_LETTER", "RETRY_EXHAUSTED"}, {"PUBLISHED", null}}) {
            UUID device = kit.device();
            terminal(device, 5, kind[0], kind[1]);
            UUID b = kit.enqueueDue(device, 0);
            assertTrue(kit.ops.candidates(10).contains(b), "predecessor " + kind[0] + "/" + kind[1] + " must not hold the device");
            assertTrue(kit.ops.claim(b, "w", LEASE).isPresent());
        }
    }

    @Test
    void anExpiredCommandFollowedByANewOneIsNotHeld() {
        UUID device = kit.device();
        UUID expired = kit.enqueue(device, Instant.now().minusSeconds(60));
        UUID next = kit.enqueueDue(device, 0);
        kit.ops.expireDue(10);
        assertEquals("EXPIRED", kit.terminalReason(expired));
        assertTrue(kit.ops.candidates(10).contains(next), "an expired command never reached the wire, so it holds nothing");
    }

    @Test
    void everyUncertainPathCreatesTheHold_timeoutLateCompletionAndLeaseLoss() throws Exception {
        for (String path : new String[]{"timeout", "late", "lease"}) {
            UUID device = kit.device();
            UUID a;
            switch (path) {
                case "timeout" -> {
                    a = kit.enqueueDue(device, 0);
                    kit.ops.complete(kit.ops.claim(a, "w", LEASE).orElseThrow(), AttemptResult.UNCERTAIN, "PUBLISH_TIMEOUT");
                }
                case "late" -> {
                    a = kit.enqueue(device, Instant.now().minusSeconds(13));      // its 15 s window ends in about 2 s
                    Claimed claimed = kit.ops.claim(a, "w", LEASE).orElseThrow();
                    Thread.sleep(3200);
                    kit.ops.complete(claimed, AttemptResult.CONFIRMED, null);     // confirmed, but recorded after the window
                }
                default -> {
                    a = kit.enqueueDue(device, 0);
                    kit.ops.claim(a, "w", LEASE).orElseThrow();
                    kit.expireLease(a);
                    kit.ops.recoverExpiredLeases(10);
                }
            }
            assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(a), path);
            UUID b = kit.enqueueDue(device, 5);
            assertFalse(kit.ops.candidates(10).contains(b), "a command after an uncertain " + path + " is held");
        }
    }

    // ------------------------------------------------------------------------------------- reporting and expiry

    @Test
    void aHeldCommandIsReportedOnceInHistoryAndInTheActionHistoryMessage() {
        UUID device = kit.device();
        terminal(device, 25, "DEAD_LETTER", "UNCERTAIN_EXPIRED");
        UUID b = kit.enqueueDue(device, 0);
        List<Held> first = kit.ops.markHeld(10);
        assertEquals(1, first.size());
        assertEquals(b, first.get(0).commandId());
        assertTrue(first.get(0).holdUntil().isAfter(Instant.now()));
        assertEquals(List.of("QUEUED"), kit.facts(b));
        assertEquals("HELD_UNCERTAIN_PREDECESSOR", kit.jdbc.queryForObject("SELECT reason_code FROM command_state_history WHERE command_id = ?", String.class, b));
        assertTrue(kit.actionMessage(b).startsWith("Held:"), kit.actionMessage(b));
        assertEquals("ACCEPTED_FOR_DISPATCH", kit.actionStatus(b), "still accepted and queued, not failed");
        assertEquals("QUEUED", kit.state(b));
        assertTrue(kit.ops.markHeld(10).isEmpty(), "reported once per command");
        assertEquals(List.of("QUEUED"), kit.facts(b));
    }

    @Test
    void aCommandWhoseWindowEndsDuringTheHoldExpiresVisiblyAndNeverSent() throws Exception {
        UUID device = kit.device();
        terminal(device, 10, "DEAD_LETTER", "UNCERTAIN_EXPIRED");         // hold lasts about 30 s more
        UUID b = kit.enqueue(device, Instant.now().minusSeconds(13));      // its own 15 s window ends in about 2 s
        kit.ops.markHeld(10);
        Thread.sleep(3200);
        var swept = kit.ops.expireDue(10);
        assertEquals(1, swept.size());
        assertEquals("EXPIRED", kit.terminalReason(b));
        assertEquals("HELD_BY_UNCERTAIN_PREDECESSOR", kit.jdbc.queryForObject("SELECT last_error_code FROM command_outbox WHERE command_id = ?", String.class, b));
        assertEquals(0, kit.attempts(b), "never sent");
        assertEquals("FAILURE", kit.actionStatus(b));
        assertTrue(kit.actionMessage(b).contains("held behind an earlier command whose delivery outcome was uncertain"), kit.actionMessage(b));
        assertEquals(List.of("QUEUED", "DEAD_LETTERED"), kit.facts(b));
    }

    // ----------------------------------------------------------------------------------- restart and instances

    @Test
    void theHoldIsDurableAcrossARestartAndIdenticalForTwoInstances() throws Exception {
        UUID device = kit.device();
        terminal(device, 25, "DEAD_LETTER", "UNCERTAIN_EXPIRED");
        UUID b = kit.enqueueDue(device, 0);
        FakeBroker broker = new FakeBroker();
        try (OutboxTestKit second = new OutboxTestKit(db)) {
            var one = new CommandOutboxDispatcher(kit.ops, broker, FakeBroker.settings(2, Duration.ofSeconds(1), Duration.ofSeconds(3)),
                    FakeBroker.listenerProvider(), FakeBroker.noMeters());
            var two = new CommandOutboxDispatcher(second.ops, broker, FakeBroker.settings(2, Duration.ofSeconds(1), Duration.ofSeconds(3)),
                    FakeBroker.listenerProvider(), FakeBroker.noMeters());
            for (int i = 0; i < 6; i++) { assertEquals(0, one.runPass()); assertEquals(0, two.runPass()); Thread.sleep(100); }
            assertTrue(broker.calls.isEmpty(), "neither instance sent the held command");
        }
        // "restart": brand new connections and dispatchers over the same database see the same hold
        try (OutboxTestKit restarted = new OutboxTestKit(db)) {
            var fresh = new CommandOutboxDispatcher(restarted.ops, broker, FakeBroker.settings(2, Duration.ofSeconds(1), Duration.ofSeconds(3)),
                    FakeBroker.listenerProvider(), FakeBroker.noMeters());
            assertEquals(0, fresh.runPass());
            assertTrue(broker.calls.isEmpty());
            assertEquals("QUEUED", restarted.state(b));
        }
    }

    @Test
    void theDispatcherNeverSendsAHeldCommandAndReportsTheHoldToItsListener() throws Exception {
        UUID device = kit.device();
        terminal(device, 25, "DEAD_LETTER", "UNCERTAIN_EXPIRED");
        UUID b = kit.enqueueDue(device, 0);
        FakeBroker broker = new FakeBroker();
        var listener = new FakeBroker.RecordingListener();
        var dispatcher = new CommandOutboxDispatcher(kit.ops, broker, FakeBroker.settings(2, Duration.ofSeconds(1), Duration.ofSeconds(3)),
                FakeBroker.listenerProvider(listener), FakeBroker.noMeters());
        for (int i = 0; i < 8; i++) { dispatcher.runPass(); Thread.sleep(100); }
        assertTrue(broker.calls.isEmpty());
        assertEquals(1, listener.held.size(), "the hold is reported exactly once");
        assertEquals(b, listener.held.get(0).commandId());
    }

    @Test
    void aRealUncertainAttemptHoldsTheNextCommandEndToEndThroughTheDispatcher() throws Exception {
        FakeBroker broker = new FakeBroker();
        broker.behaviour = (topic, bytes) -> BrokerCommandPublisher.Attempt.uncertain("PUBLISH_TIMEOUT");   // A: the confirmation never came
        var dispatcher = new CommandOutboxDispatcher(kit.ops, broker, FakeBroker.settings(2, Duration.ofSeconds(1), Duration.ofSeconds(3)),
                FakeBroker.listenerProvider(), FakeBroker.noMeters());
        UUID device = kit.device();
        UUID a = kit.enqueueDue(device, 0);                 // A = ON
        UUID b = kit.enqueueDue(device, 1);                 // B = OFF, accepted right behind it
        dispatcher.runPass();
        await(() -> "DEAD_LETTER".equals(kit.state(a)), "A uncertain", 20);
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(a));
        broker.behaviour = (topic, bytes) -> BrokerCommandPublisher.Attempt.confirmed();
        for (int i = 0; i < 10; i++) { dispatcher.runPass(); Thread.sleep(100); }
        assertEquals(1, broker.calls.size(), "only A was ever sent; B is held");
        assertEquals("QUEUED", kit.state(b));
        assertEquals(0, broker.callsFor(b.toString()));
    }

    @Test
    void aWrongClockCannotHoldADeviceLongerThanTheCapOrReleaseItEarly() {
        long now = dbEpoch();
        try (Connection c = kit.dataSource.getConnection()) {
            // payload timestamp an hour in the FUTURE (a fast writer clock) but created one second ago on the database clock
            UUID device = kit.device();
            UUID command = OutboxSql.action(c, device, "FAILURE", Instant.ofEpochSecond(now));
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.ofEpochSecond(now + 3600));
            row.putAll(Map.of("state", "DEAD_LETTER", "attempt_count", 1, "terminal_reason", "UNCERTAIN_EXPIRED", "last_error_code", "TEST"));
            row.put("next_attempt_at", null);
            row.put("terminal_at", Timestamp.from(Instant.ofEpochSecond(now)));
            row.put("created_at", Timestamp.from(Instant.ofEpochSecond(now - 1)));
            OutboxSql.insert(c, row);
            UUID b = kit.enqueueDue(device, 0);
            assertFalse(kit.ops.candidates(10).contains(b), "held");
            var held = kit.ops.markHeld(10);
            assertEquals(1, held.size());
            long secondsLeft = held.get(0).holdUntil().getEpochSecond() - now;
            assertTrue(secondsLeft <= CommandDeliveryLimits.UNCERTAIN_DELIVERY_HOLD_CAP.toSeconds() && secondsLeft >= 55,
                    "the hold is capped at creation + 60 s on the database clock, not timestamp + 40 s an hour away: " + secondsLeft);

            // payload timestamp an hour in the PAST (a slow writer clock): the firmware would reject it as STALE, so nothing is held
            UUID device2 = kit.device();
            UUID old = OutboxSql.action(c, device2, "FAILURE", Instant.ofEpochSecond(now - 3600));
            Map<String, Object> past = OutboxSql.queued(old, device2, Instant.ofEpochSecond(now - 3600));
            past.putAll(Map.of("state", "DEAD_LETTER", "attempt_count", 1, "terminal_reason", "UNCERTAIN_EXPIRED", "last_error_code", "TEST"));
            past.put("next_attempt_at", null);
            past.put("terminal_at", Timestamp.from(Instant.ofEpochSecond(now - 3600)));
            OutboxSql.insert(c, past);
            UUID b2 = kit.enqueueDue(device2, 1);
            assertTrue(kit.ops.candidates(10).contains(b2), "a long-stale uncertain command holds nothing");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
