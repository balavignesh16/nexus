package com.nexus.action.crash;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash-recovery campaign, one REAL backend process per node (ledger P4-059 to P4-067, R15). Processes are stopped without any graceful
 * shutdown, replacements are started, and the assertions are made on durable rows, on the broker's own count of what it received per
 * command id, and on the read-only lifecycle API.
 *
 * Alignment with the implemented design (contract R37): the original R15 table said an attempt interrupted after the claim is "retried
 * within the window". The implemented and safer behaviour (P4.3) is that an IN_FLIGHT attempt whose lease expired is UNCERTAIN (the message
 * may or may not have reached the broker) and ends as DEAD_LETTER UNCERTAIN_EXPIRED: it is never resent, so a crash can never cause a
 * duplicate. The scenarios below assert that behaviour, including that the broker received the command at most once.
 *
 * "Restart" inside the 20 second delivery window: a cold Spring Boot start takes a large part of that window, so where the command must still
 * be live after the restart, the replacement process is started BEFORE the first one dies with its dispatcher gated (crash.gate.file); it is a
 * full separate process that has not done any delivery work, and the test releases it only after the first process is dead. Where the
 * window is irrelevant (uncertain recovery, ACK timeout, expiry after downtime) the replacement is a true cold start.
 */
@Tag("crash")
class CommandCrashRecoveryTest extends CrashTestBase {

    private Path gate() throws Exception {
        Path dir = Files.createTempDirectory("crash-gate");
        return dir.resolve("open");           // does not exist yet: the gate is closed
    }

    private static void open(Path gate) throws Exception {
        Files.writeString(gate, "go");
    }

    private void assertNoDuplicatePublicationAfter(UUID id, long millis) throws Exception {
        int before = infra.publishedCount(id);
        Thread.sleep(millis);
        assertEquals(before, infra.publishedCount(id), "the broker received no further copy of the command");
    }

    // ----------------------------------------------------------------------------------------- P4-059

    @Test
    @Tag("crash-smoke")
    @Timeout(420)
    void killAfterAcceptanceBeforeClaimTheCommandIsPublishedExactlyOnceAfterRestart() throws Exception {
        UUID device = device();
        Path gate = gate();
        Node standby = start(Map.of("crash.gate.file", gate.toString()));
        Node first = start(Map.of("crash.kill", "AFTER_ACCEPTANCE"));
        first.sendLed(device, true);
        assertEquals(137, first.awaitExit(Duration.ofSeconds(60)), "the process stopped at its kill point");
        UUID id = latestCommand(device);

        assertEquals("QUEUED", outboxState(id), "accepted and durable, never claimed");
        assertEquals(List.of("ACCEPTED", "QUEUED"), facts(id));
        assertEquals(0, infra.publishedCount(id));

        open(gate);                                    // the replacement process starts delivering
        await(() -> "PUBLISHED".equals(outboxState(id)), "publication after the restart", 60);
        assertEquals(List.of("ACCEPTED", "QUEUED", "ATTEMPT_STARTED", "PUBLISHED"), facts(id));
        assertEquals(1, infra.publishedCount(id), "published exactly once to the broker");
        assertNoDuplicatePublicationAfter(id, 3000);
        assertEquals(1, infra.jdbc.queryForObject("SELECT attempt_count FROM command_outbox WHERE command_id = ?", Integer.class, id));
        assertGaplessHistory(id);
        assertTrue(standby.alive());
    }

    // ----------------------------------------------------------------------------------------- P4-060

    @Test
    @Timeout(420)
    void killAfterClaimBeforePublishLeaseExpiryRecoversItAsUncertainWithTheAttemptCountedAndNothingResent() throws Exception {
        UUID device = device();
        Node first = start(Map.of("crash.kill", "AFTER_CLAIM"));
        first.sendLed(device, true);
        assertEquals(137, first.awaitExit(Duration.ofSeconds(60)));
        UUID id = latestCommand(device);

        assertEquals("IN_FLIGHT", outboxState(id), "claimed, with a lease, when the process died");
        assertEquals(1, infra.jdbc.queryForObject("SELECT attempt_count FROM command_outbox WHERE command_id = ?", Integer.class, id));
        assertEquals(0, infra.publishedCount(id), "the broker was never asked");

        start();
        await(() -> "DEAD_LETTER".equals(outboxState(id)), "lease expiry recovery", 90);
        assertEquals("UNCERTAIN_EXPIRED", infra.jdbc.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, id));
        assertEquals("LEASE_EXPIRED_UNCERTAIN", infra.jdbc.queryForObject("SELECT last_error_code FROM command_outbox WHERE command_id = ?", String.class, id));
        assertEquals(1, infra.jdbc.queryForObject("SELECT attempt_count FROM command_outbox WHERE command_id = ?", Integer.class, id), "the attempt is counted honestly, once");
        assertNull(infra.jdbc.queryForObject("SELECT first_publish_confirmed_at FROM command_outbox WHERE command_id = ?", java.sql.Timestamp.class, id),
                "no broker confirmation is ever claimed");
        assertTrue(facts(id).contains("DEAD_LETTERED"));
        assertNoDuplicatePublicationAfter(id, 3000);
        assertEquals(0, infra.publishedCount(id), "an uncertain attempt is never resent");
        assertGaplessHistory(id);
    }

    // ----------------------------------------------------------------------------------------- P4-061

    @Test
    @Tag("crash-smoke")
    @Timeout(420)
    void killAfterPublishBeforeTheOutcomeRecoveryRecordsAnUncertainAttemptAndTheAckStillCorrelates() throws Exception {
        UUID device = device();
        Node first = start(Map.of("crash.kill", "AFTER_PUBLISH"));
        first.sendLed(device, true);
        assertEquals(137, first.awaitExit(Duration.ofSeconds(60)));
        UUID id = latestCommand(device);

        assertEquals(1, infra.publishedCount(id), "the broker had the command before the process died");
        assertEquals("IN_FLIGHT", outboxState(id), "but the outcome was never recorded");

        Node second = start();
        await(() -> "DEAD_LETTER".equals(outboxState(id)), "lease expiry recovery", 90);
        assertEquals("UNCERTAIN_EXPIRED", infra.jdbc.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, id));
        assertNoDuplicatePublicationAfter(id, 3000);
        assertEquals(1, infra.publishedCount(id), "recovery never duplicated the message");

        // the device did receive it: its acknowledgement arrives over the real broker and correlates to the same command
        infra.deviceAck(device, id, "ACCEPTED", System.currentTimeMillis() / 1000);
        await(() -> facts(id).contains("ACK_ACCEPTED"), "the acknowledgement to be recorded", 60);
        assertTrue(facts(id).contains("ACK_AFTER_TERMINAL"), "recorded as an ACK after the terminal state: " + facts(id));
        assertEquals("DEAD_LETTER", outboxState(id), "the obligation is not resurrected");

        JsonNode lifecycle = second.get("/api/v1/actions/" + id + "/lifecycle");
        assertEquals("ACCEPTED", lifecycle.at("/ack/status").asText());
        assertEquals("DEAD_LETTER", lifecycle.at("/delivery/state").asText());
        assertEquals("UNCERTAIN_EXPIRED", lifecycle.at("/delivery/terminalReason").asText());
        assertTrue(lifecycle.at("/delivery/firstPublishConfirmedAt").isMissingNode(), "NEXUS never claims the confirmation it did not record");
        assertGaplessHistory(id);
    }

    // ----------------------------------------------------------------------------------------- P4-062

    @Test
    @Timeout(420)
    void killDuringTheOutcomeTransactionLeavesNoPartialStateAndRecoveryCompletesTheCommand() throws Exception {
        UUID device = device();
        Node first = start(Map.of("crash.kill", "IN_OUTCOME_TX"));
        first.sendLed(device, true);
        assertEquals(137, first.awaitExit(Duration.ofSeconds(60)));
        UUID id = latestCommand(device);

        assertEquals("IN_FLIGHT", outboxState(id), "the outbox half of the outcome rolled back");
        assertEquals("ACCEPTED_FOR_DISPATCH", infra.jdbc.queryForObject("SELECT status FROM action_history WHERE request_id = ?", String.class, id),
                "and so did the action history half");
        assertNull(infra.jdbc.queryForObject("SELECT published_at FROM action_history WHERE request_id = ?", java.sql.Timestamp.class, id));
        assertNull(infra.jdbc.queryForObject("SELECT first_publish_confirmed_at FROM command_outbox WHERE command_id = ?", java.sql.Timestamp.class, id));
        assertFalse(facts(id).contains("PUBLISHED"), "no PUBLISHED fact survived: " + facts(id));
        assertEquals(1, infra.publishedCount(id), "the message itself had already reached the broker");

        start();
        await(() -> "DEAD_LETTER".equals(outboxState(id)), "recovery", 90);
        assertEquals("UNCERTAIN_EXPIRED", infra.jdbc.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, id));
        assertNoDuplicatePublicationAfter(id, 3000);
        assertEquals(1, infra.publishedCount(id));
        assertGaplessHistory(id);
    }

    // ----------------------------------------------------------------------------------------- P4-063

    /**
     * A manual command is refused at acceptance while the broker is down, so an attempt can only fail as NOT_SENT when the broker goes away AFTER
     * acceptance. The node is therefore held (crash.gate.file) while the command is accepted, the broker is cut and the client notices, and only
     * then released: its first attempt finds the client disconnected.
     */
    private UUID acceptThenLoseTheBroker(Node held, Path heldGate, UUID device) throws Exception {
        held.sendLed(device, true);
        UUID id = latestCommand(device);
        assertEquals("QUEUED", outboxState(id));
        infra.brokerProxy.cut();
        Thread.sleep(1500);                                                   // the client notices the lost connection
        open(heldGate);
        return id;
    }

    @Test
    @Timeout(420)
    void aPendingRetrySurvivesARestartAndIsPublishedOnItsScheduleWhileTheWindowLasts() throws Exception {
        UUID device = device();
        Path standbyGate = gate(), firstGate = gate();
        Map<String, String> slowRetry = Map.of("nexus.command.outbox.initial-backoff", "PT6S", "nexus.command.outbox.max-backoff", "PT10S",
                "nexus.command.outbox.max-attempts", "2");          // the startup validation requires the worst-case schedule to fit the window
        // the standby talks to the broker directly, so the outage that the first node suffers does not disturb its client
        Map<String, String> direct = new java.util.LinkedHashMap<>(withGate(slowRetry, standbyGate));
        direct.put("mqtt.broker.url", "tcp://" + infra.mosquitto.getHost() + ":" + infra.mosquitto.getMappedPort(1883));
        Node standby = start(direct);
        Node first = start(withGate(slowRetry, firstGate));
        UUID id = acceptThenLoseTheBroker(first, firstGate, device);
        await(() -> "RETRY_WAIT".equals(outboxState(id)), "the first attempt to fail and a retry to be scheduled", 30);
        java.sql.Timestamp scheduled = infra.jdbc.queryForObject("SELECT next_attempt_at FROM command_outbox WHERE command_id = ?", java.sql.Timestamp.class, id);
        first.kill();                                                          // no graceful shutdown
        infra.brokerProxy.restore();

        assertEquals("RETRY_WAIT", outboxState(id), "the pending retry is still there");
        assertEquals(scheduled, infra.jdbc.queryForObject("SELECT next_attempt_at FROM command_outbox WHERE command_id = ?", java.sql.Timestamp.class, id),
                "with its schedule untouched");
        assertEquals(0, infra.publishedCount(id));

        // release the standby only after the scheduled instant, so that "on its schedule" is a real claim
        await(() -> infra.jdbc.queryForObject("SELECT now() > next_attempt_at FROM command_outbox WHERE command_id = ?", Boolean.class, id), "the scheduled retry instant", 30);
        open(standbyGate);
        await(() -> "PUBLISHED".equals(outboxState(id)), "the retry to be published by the replacement", 60);
        assertEquals(1, infra.publishedCount(id), "published once");
        assertTrue(!infra.jdbc.queryForObject("SELECT first_publish_confirmed_at FROM command_outbox WHERE command_id = ?", java.sql.Timestamp.class, id).before(scheduled),
                "not before the scheduled time");
        assertTrue(infra.jdbc.queryForObject("SELECT attempt_count FROM command_outbox WHERE command_id = ?", Integer.class, id) <= 2);
        assertGaplessHistory(id);
        assertTrue(standby.alive());
    }

    @Test
    @Timeout(420)
    void aRetryWhoseWindowElapsedDuringDowntimeExpiresAndIsNeverPublished() throws Exception {
        UUID device = device();
        Path firstGate = gate();
        Map<String, String> shortWindow = Map.of("nexus.command.outbox.delivery-window", "PT6S", "nexus.command.outbox.max-attempts", "2",
                "nexus.command.outbox.attempt-timeout", "PT1S", "nexus.command.outbox.initial-backoff", "PT2S", "nexus.command.outbox.max-backoff", "PT2S");
        Node first = start(withGate(shortWindow, firstGate));
        UUID id = acceptThenLoseTheBroker(first, firstGate, device);
        await(() -> "RETRY_WAIT".equals(outboxState(id)) || "DEAD_LETTER".equals(outboxState(id)), "the first attempt to fail", 30);
        first.kill();
        infra.brokerProxy.restore();                                           // the broker is healthy again: only the window can stop a publication
        String stateAtKill = outboxState(id);

        await(() -> infra.jdbc.queryForObject("SELECT now() > expires_at FROM command_outbox WHERE command_id = ?", Boolean.class, id), "the window to elapse during the downtime", 30);
        start(shortWindow);
        await(() -> "DEAD_LETTER".equals(outboxState(id)), "the expired retry to be dead-lettered", 60);
        if ("RETRY_WAIT".equals(stateAtKill))
            assertEquals("EXPIRED", infra.jdbc.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, id));
        assertNoDuplicatePublicationAfter(id, 3000);
        assertEquals(0, infra.publishedCount(id), "a stale command is never published");
        assertGaplessHistory(id);
    }

    // ----------------------------------------------------------------------------------------- P4-064

    @Test
    @Timeout(900)
    void aRestartStormNeverExceedsTheAttemptCapAndNeverRepeatsOrSkipsAHistorySequence() throws Exception {
        List<UUID> devices = List.of(device(), device(), device());
        Map<String, String> flaky = Map.of("nexus.command.outbox.initial-backoff", "PT1S", "nexus.command.outbox.max-backoff", "PT2S");
        for (int round = 0; round < 4; round++) {
            infra.brokerProxy.restore();
            Path gate = gate();
            Node node = start(round % 2 == 0 ? withGate(flaky, gate) : flaky);
            for (UUID device : devices) for (int i = 0; i < 2; i++) node.sendLed(device, i % 2 == 0);
            if (round % 2 == 0) {                                              // accepted while held; the broker goes away; then the attempts start
                infra.brokerProxy.cut();
                Thread.sleep(1500);
                open(gate);
            }
            Thread.sleep(1500L + 700L * round);
            node.kill();                                                       // no graceful shutdown, in the middle of whatever was happening
        }
        infra.brokerProxy.restore();
        Node last = start(flaky);                                              // the survivor that finishes everything

        await(() -> infra.jdbc.queryForObject("SELECT count(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) == 0,
                "every obligation to reach a terminal state", 240);
        List<UUID> commands = infra.jdbc.queryForList("SELECT command_id FROM command_outbox", UUID.class);
        assertFalse(commands.isEmpty());
        for (UUID id : commands) {
            assertTrue(infra.jdbc.queryForObject("SELECT attempt_count <= max_attempts FROM command_outbox WHERE command_id = ?", Boolean.class, id),
                    "attempts never exceed the cap for " + id);
            assertGaplessHistory(id);
            assertTrue(infra.publishedCount(id) <= 1, "the broker never received a command twice: " + id + " x" + infra.publishedCount(id));
            long terminal = facts(id).stream().filter(f -> f.equals("PUBLISHED") || f.equals("DEAD_LETTERED")).count();
            assertEquals(1, terminal, "exactly one terminal fact for " + id + ": " + facts(id));
        }
        assertTrue(last.alive());
    }

    // ----------------------------------------------------------------------------------------- P4-065

    @Test
    @Tag("crash-smoke")
    @Timeout(420)
    void anAckTimeoutThatPassedDuringDowntimeIsPersistedAfterTheRestart() throws Exception {
        UUID device = device();
        Map<String, String> shortAck = Map.of("nexus.command.ack-timeout", "PT3S");
        Node first = start(shortAck);
        UUID id = first.sendLedAndGetId(device, true);
        await(() -> "PUBLISHED".equals(outboxState(id)), "publication", 60);
        first.kill();
        assertFalse(facts(id).contains("ACK_TIMED_OUT"));
        await(() -> infra.jdbc.queryForObject("SELECT now() > ack_deadline_at FROM action_history WHERE request_id = ?", Boolean.class, id),
                "the ACK deadline to pass while nothing was running", 30);

        Node second = start(shortAck);
        await(() -> facts(id).contains("ACK_TIMED_OUT"), "the timeout to be persisted by the restarted process", 60);
        assertEquals(1, facts(id).stream().filter(f -> f.equals("ACK_TIMED_OUT")).count(), "one timeout fact");
        assertEquals("PUBLISHED", outboxState(id), "a timeout does not change the delivery record");
        assertEquals(1, infra.publishedCount(id), "and nothing was resent");
        JsonNode lifecycle = second.get("/api/v1/actions/" + id + "/lifecycle");
        assertTrue(lifecycle.at("/ack/timedOut").asBoolean());
        assertEquals("PUBLISHED", lifecycle.at("/delivery/state").asText());

        infra.deviceAck(device, id, "ACCEPTED", System.currentTimeMillis() / 1000);          // a late acknowledgement is a new fact, not an erasure
        await(() -> facts(id).contains("ACK_AFTER_TIMEOUT"), "the late acknowledgement to be recorded", 60);
        assertTrue(facts(id).contains("ACK_TIMED_OUT"), "the timeout fact is kept");
        assertGaplessHistory(id);
    }

    // ----------------------------------------------------------------------------------------- P4-066

    @Test
    @Timeout(420)
    void aDatabaseOutageAtTheOutcomeWriteIsRecoveredByLeaseExpiryAndTheCommandIsNotLost() throws Exception {
        UUID device = device();
        UUID later = device();                                                 // created before the process starts, so it is hydrated
        Path pauseDir = Files.createTempDirectory("crash-pause");
        Node node = start(Map.of("crash.kill", "PAUSE_AFTER_PUBLISH", "crash.pause.dir", pauseDir.toString()));
        node.sendLed(device, true);
        await(() -> Files.exists(pauseDir.resolve("paused")), "the publish to be confirmed", 60);
        UUID id = latestCommand(device);
        assertEquals(1, infra.publishedCount(id));

        infra.dbProxy.cut();                                                   // the database vanishes exactly when the outcome is about to be written
        Files.writeString(pauseDir.resolve("resume"), "go");
        Thread.sleep(3000);
        infra.dbProxy.restore();

        assertEquals("IN_FLIGHT", outboxState(id), "the outcome never reached the database");
        await(() -> "DEAD_LETTER".equals(outboxState(id)), "lease expiry to recover the command", 90);
        assertEquals("UNCERTAIN_EXPIRED", infra.jdbc.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, id));
        assertEquals(1, infra.publishedCount(id), "the command is not lost and not duplicated");
        assertGaplessHistory(id);

        // the same process is healthy again afterwards
        Files.deleteIfExists(pauseDir.resolve("paused"));
        UUID next = node.sendLedAndGetId(later, false);
        await(() -> Files.exists(pauseDir.resolve("paused")), "the next publish", 60);
        await(() -> "PUBLISHED".equals(outboxState(next)), "the next command to be published normally", 60);
    }

    // ----------------------------------------------------------------------------------------- P4-067

    @Test
    @Timeout(420)
    void aBrokerOutageLongerThanTheWindowDeadLettersTheCommandVisiblyAndNothingStaleIsPublishedAfterRecovery() throws Exception {
        UUID device = device();
        Path gate = gate();
        Node node = start(withGate(Map.of(), gate));
        UUID id = acceptThenLoseTheBroker(node, gate, device);
        await(() -> "DEAD_LETTER".equals(outboxState(id)), "the command to be dead-lettered while the broker is away", 60);
        // Every permitted attempt found the client disconnected (proven NOT_SENT), so the attempts run out before the window does; had the
        // dispatcher been slower the window would have ended first. Either way it is a visible dead letter, never an uncertain or silent one.
        String reason = infra.jdbc.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, id);
        assertTrue(reason.equals("RETRY_EXHAUSTED") || reason.equals("EXPIRED"), "dead-lettered for a delivery reason: " + reason);
        JsonNode lifecycle = node.get("/api/v1/actions/" + id + "/lifecycle");
        assertEquals("DEAD_LETTER", lifecycle.at("/delivery/state").asText(), "visible through the lifecycle API");
        assertEquals(reason, lifecycle.at("/delivery/terminalReason").asText());
        assertTrue(lifecycle.at("/explanation").toString().contains("Dead-lettered"), lifecycle.at("/explanation").toString());

        // keep the broker away for longer than the delivery window, then bring it back: only the expired command could still be (wrongly) sent
        await(() -> infra.jdbc.queryForObject("SELECT now() > expires_at FROM command_outbox WHERE command_id = ?", Boolean.class, id), "the window to end during the outage", 60);
        infra.brokerProxy.restore();
        UUID fresh = null;
        long end = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (fresh == null && System.nanoTime() < end) {                      // the client reconnects on its own schedule
            UUID candidate;
            try { candidate = node.sendLedAndGetId(device, false); }
            catch (AssertionError refused) { Thread.sleep(1000); continue; }       // refused while the client is still disconnected
            await(() -> outboxState(candidate) != null && !List.of("QUEUED", "IN_FLIGHT", "RETRY_WAIT").contains(outboxState(candidate)), "the new command to resolve", 30);
            if ("PUBLISHED".equals(outboxState(candidate))) fresh = candidate;
        }
        assertNotNull(fresh, "after recovery new commands are published again");
        assertEquals(0, infra.publishedCount(id), "the dead-lettered command was never published, not even after the broker came back");
    }

    private static Map<String, String> withGate(Map<String, String> base, Path gate) {
        Map<String, String> m = new java.util.LinkedHashMap<>(base);
        m.put("crash.gate.file", gate.toString());
        return m;
    }
}
