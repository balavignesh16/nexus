package com.nexus.action.crash;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Several real backend processes against one PostgreSQL and one broker (ledger P4-112, P4-107) and a command flood against the bounded
 * queues (P4-093). Counts come from the broker itself: how many copies of each command id it actually received.
 */
@Tag("crash")
class CommandMultiInstanceAndFloodTest extends CrashTestBase {

    // ----------------------------------------------------------------------------------------- P4-112

    @Test
    @Timeout(600)
    void twoBackendInstancesDeliverEveryCommandExactlyOnceToTheBroker() throws Exception {
        List<UUID> devices = new ArrayList<>();
        for (int i = 0; i < 5; i++) devices.add(device());
        Node a = start();
        Node b = start();

        List<UUID> ids = new ArrayList<>();
        for (int round = 0; round < 4; round++)
            for (UUID device : devices) {
                Node via = (ids.size() % 2 == 0) ? a : b;                         // the requests alternate between the two processes
                ids.add(via.sendLedAndGetId(device, round % 2 == 0));
                Thread.sleep(40);
            }

        await(() -> infra.jdbc.queryForObject("SELECT count(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) == 0,
                "every command to reach a terminal state", 120);
        for (UUID id : ids) {
            assertEquals("PUBLISHED", outboxState(id), "published, not dead-lettered: " + id + " " + facts(id));
            assertEquals(1, infra.publishedCount(id), "the broker received exactly one copy of " + id);
            assertEquals(1, infra.jdbc.queryForObject("SELECT attempt_count FROM command_outbox WHERE command_id = ?", Integer.class, id),
                    "one claim, by one of the two processes");
            assertEquals(List.of("ACCEPTED", "QUEUED", "ATTEMPT_STARTED", "PUBLISHED"), facts(id));
            assertGaplessHistory(id);
        }
        Thread.sleep(3000);
        for (UUID id : ids) assertEquals(1, infra.publishedCount(id), "and still exactly one after the processes had time to misbehave: " + id);
        assertTrue(a.alive() && b.alive());
    }

    // ----------------------------------------------------------------------------------------- P4-107

    @Test
    @Timeout(600)
    void switchingTheFlagOffDrainsEveryQueuedCommandToATerminalStateAndNothingIsStranded() throws Exception {
        UUID device1 = device(), device2 = device(), device3 = device();
        Path gate = Files.createTempDirectory("crash-gate").resolve("open");
        Path neverOpened = Files.createTempDirectory("crash-gate").resolve("never");
        // The flag-OFF process (the rollback) is up and waiting; the flag-ON process accepts commands but its dispatcher is held, then both die/switch.
        Node rolledBack = start(Map.of("nexus.command.outbox.enabled", "false", "crash.gate.file", gate.toString()));
        Node flagOn = start(Map.of("crash.gate.file", neverOpened.toString()));
        List<UUID> ids = new ArrayList<>();
        for (UUID device : List.of(device1, device2, device3)) ids.add(flagOn.sendLedAndGetId(device, true));
        ids.add(flagOn.sendLedAndGetId(device1, false));                          // a second command queued behind the first on one device
        for (UUID id : ids) assertEquals("QUEUED", outboxState(id));
        flagOn.kill();                                                            // the operator turns the feature off: the old process is gone

        Files.writeString(gate, "go");
        await(() -> infra.jdbc.queryForObject("SELECT count(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) == 0,
                "the rolled-back process to drain every queued command", 120);
        for (UUID id : ids) {
            String state = outboxState(id);
            assertTrue(state.equals("PUBLISHED") || state.equals("DEAD_LETTER"), "terminal, not stranded: " + id + " " + state);
            assertTrue(infra.publishedCount(id) <= 1, "never sent twice");
        }
        // with the flag OFF a NEW command no longer creates an outbox row (the legacy path is back)
        int rowsBefore = infra.jdbc.queryForObject("SELECT count(*) FROM command_outbox", Integer.class);
        int status = rolledBack.sendLed(device2, true);
        assertTrue(status / 100 == 2 || status == 409 || status == 422 || status == 400, "the legacy path answered: " + status);
        assertEquals(rowsBefore, (int) infra.jdbc.queryForObject("SELECT count(*) FROM command_outbox", Integer.class), "flag OFF creates no new delivery obligation");
    }

    // ----------------------------------------------------------------------------------------- P4-093

    @Test
    @Timeout(900)
    void aCommandFloodStaysWithinTheQueueCapsAndEveryAcceptedCommandReachesATerminalStateOrIsRefusedWithATypedRejection() throws Exception {
        List<UUID> devices = new ArrayList<>();
        for (int i = 0; i < 6; i++) devices.add(device());
        int globalCap = 12, deviceCap = 3;
        Node node = start(Map.of("nexus.command.flow.global-queue-cap", "" + globalCap, "nexus.command.flow.device-queue-cap", "" + deviceCap,
                "nexus.command.flow.publish-rate-per-second", "10", "nexus.command.outbox.delivery-window", "PT20S"));
        // the broker is slow to confirm, so the queues actually fill
        long heapBefore = heapUsed(node);

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        AtomicInteger accepted = new AtomicInteger(), refused = new AtomicInteger(), other = new AtomicInteger(), untyped = new AtomicInteger();
        AtomicLong maxUnresolved = new AtomicLong();
        ExecutorService senders = Executors.newFixedThreadPool(16);
        List<Future<?>> work = new ArrayList<>();
        int total = 240;
        for (int i = 0; i < total; i++) {
            final UUID device = devices.get(i % devices.size());
            final boolean value = i % 2 == 0;
            work.add(senders.submit((Callable<Void>) () -> {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + node.port + "/api/v1/devices/" + device + "/commands"))
                        .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":" + value + "}")).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) accepted.incrementAndGet();
                else if (response.statusCode() == 429) {
                    refused.incrementAndGet();
                    JsonNode body = JSON.readTree(response.body());
                    if (!"COMMAND_QUEUE_FULL".equals(body.path("code").asText()) || body.path("scope").asText().isEmpty()) untyped.incrementAndGet();
                } else other.incrementAndGet();
                return null;
            }));
        }
        Thread sampler = new Thread(() -> {
            try {
                while (work.stream().anyMatch(f -> !f.isDone())) {
                    long unresolved = infra.jdbc.queryForObject("SELECT count(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Long.class);
                    maxUnresolved.accumulateAndGet(unresolved, Math::max);
                    Thread.sleep(25);
                }
            } catch (InterruptedException ignored) { /* ends */ }
        });
        sampler.start();
        for (Future<?> f : work) f.get(300, TimeUnit.SECONDS);
        senders.shutdown();
        sampler.join(10_000);

        assertEquals(total, accepted.get() + refused.get() + other.get());
        assertTrue(refused.get() > 0, "the flood was large enough to hit the caps");
        assertEquals(0, other.get(), "every request was either accepted or refused with the typed 429");
        assertEquals(0, untyped.get(), "and every refusal named its code and scope");
        assertTrue(maxUnresolved.get() <= globalCap, "the unresolved queue never exceeded the global cap: " + maxUnresolved.get() + " > " + globalCap);

        await(() -> infra.jdbc.queryForObject("SELECT count(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) == 0,
                "every accepted command to reach a terminal state within its window", 120);
        int rows = infra.jdbc.queryForObject("SELECT count(*) FROM command_outbox o JOIN action_history a ON a.request_id = o.command_id WHERE a.device_id = ANY (?)",
                Integer.class, (Object) devices.toArray(new UUID[0]));
        assertEquals(accepted.get(), rows, "a refusal wrote nothing: one outbox row per accepted command");
        assertEquals(0, infra.jdbc.queryForObject("SELECT count(*) FROM command_outbox o JOIN action_history a ON a.request_id = o.command_id"
                + " WHERE a.device_id = ANY (?) AND o.state NOT IN ('PUBLISHED','DEAD_LETTER')", Integer.class, (Object) devices.toArray(new UUID[0])));
        long publishedTwice = infra.jdbc.queryForList("SELECT command_id FROM command_outbox", UUID.class).stream().filter(id -> infra.publishedCount(id) > 1).count();
        assertEquals(0, publishedTwice, "no command reached the broker twice");
        long heapAfter = heapUsed(node);
        assertTrue(heapAfter - heapBefore < 200L * 1024 * 1024, "memory stayed bounded: " + (heapAfter - heapBefore) + " bytes of growth");
        assertFalse(node.log().toString().isEmpty());
    }

    private long heapUsed(Node node) throws Exception {
        JsonNode metric = node.get("/actuator/metrics/jvm.memory.used?tag=area:heap");
        return (long) metric.at("/measurements/0/value").asDouble();
    }
}
