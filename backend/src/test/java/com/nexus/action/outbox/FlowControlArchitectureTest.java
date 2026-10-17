package com.nexus.action.outbox;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level proofs for P4.6 (contract R18; ledger P4-092). Behaviour is proven by the integration tests; these fail the build if flow
 * control can be bypassed structurally or an unbounded in-memory queue is introduced on the ingestion or dispatch path.
 */
class FlowControlArchitectureTest {

    private static final Path MAIN = locate("src/main/java");

    private static Path locate(String relative) {
        Path p = Path.of(relative);
        if (!Files.exists(p)) p = Path.of("backend").resolve(relative);
        return p.normalize();
    }

    private static List<Path> sources(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> filesMatching(Pattern pattern) throws IOException {
        return sources(MAIN).stream().filter(f -> pattern.matcher(read(f)).find()).map(f -> f.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String source(String name) throws IOException {
        return sources(MAIN).stream().filter(f -> f.getFileName().toString().equals(name)).map(FlowControlArchitectureTest::read).findFirst().orElseThrow();
    }

    @Test
    void theOnlyClaimOfAnOutboxRowIsInsideTheGatedClaimOfTheDeliveryOperations() throws IOException {
        Set<String> claimers = filesMatching(Pattern.compile("state\\s*=\\s*'IN_FLIGHT'\\s*,\\s*attempt_count\\s*=\\s*attempt_count\\s*\\+\\s*1"));
        assertEquals(Set.of("OutboxDeliveryOperations.java"), claimers, "a second claim statement would bypass the publish gate");
        String operations = source("OutboxDeliveryOperations.java");
        int claimStatement = operations.indexOf("attempt_count = attempt_count + 1");
        int gate = operations.indexOf("publishGate.permit()");
        assertTrue(gate > 0 && claimStatement > gate, "the gate decision comes before the claim statement, in the same method and transaction");
        String tryClaim = operations.substring(operations.indexOf("public ClaimResult tryClaim("), operations.indexOf("private static boolean isUniqueViolation"));
        assertFalse(tryClaim.contains("catch (RuntimeException") || tryClaim.contains("catch (Exception") || tryClaim.contains("catch (Throwable"),
                "a failure of the gate (or of anything else in the claim) is never swallowed: no decision, no claim (fail closed)");
    }

    @Test
    void theDispatcherClaimsOnlyThroughTheGatedClaimAndNeverTalksToTheBrokerBeforeAClaim() throws IOException {
        String dispatcher = source("CommandOutboxDispatcher.java");
        assertTrue(dispatcher.contains("operations.tryClaim("), "the dispatcher claims through the gated operation");
        assertFalse(dispatcher.contains("operations.claim("), "no ungated alias is used by the dispatcher");
        assertTrue(dispatcher.indexOf("operations.tryClaim(") < dispatcher.indexOf("publisher.publish("), "no publication precedes a claim");
    }

    @Test
    void onlyAcceptanceTakesTheAdmissionLockAndOnlyTheGateTakesTheGateLock() throws IOException {
        assertEquals(Set.of("CommandAcceptanceService.java"), filesMatching(Pattern.compile("admission\\.admit\\(")), "admission is consulted by acceptance only");
        assertEquals(Set.of("CommandAdmission.java", "CommandPublishGate.java"), filesMatching(Pattern.compile("pg_advisory_xact_lock\\(\\?,\\s*\\?\\)")),
                "the two flow-control locks live in their own classes");
        // two different objects in one key space, never the same, and different from the per-command history lock (a bigint key)
        assertEquals(CommandAdmission.LOCK_CLASS, CommandPublishGate.LOCK_CLASS);
        assertTrue(CommandAdmission.ADMISSION_LOCK != CommandPublishGate.GATE_LOCK);
        assertTrue(source("CommandStateHistory.java").contains("pg_advisory_xact_lock(hashtextextended(?, 0))"), "the history lock keeps its single bigint key and is still taken last");
    }

    @Test
    void noFlowControlClassEverTouchesTheDeviceRowTheBrokerOrAnExecutor() throws IOException {
        for (String name : List.of("CommandAdmission.java", "CommandPublishGate.java", "CommandFlowSettings.java", "CommandFlowMetrics.java")) {
            String text = source(name);
            assertFalse(text.contains("FROM devices"), name + " must not lock or read the device row");
            assertFalse(text.contains("org.eclipse.paho"), name + " has no MQTT client");
            assertFalse(text.contains("CommandExecutor"), name + " publishes nothing");
            assertFalse(text.toLowerCase().contains("requeue"), name + " cannot requeue");
        }
    }

    @Test
    void thePublishGateIsNeverHeldAcrossNetworkIo() throws IOException {
        String dispatcher = source("CommandOutboxDispatcher.java");
        int deliver = dispatcher.indexOf("void deliver(");
        assertTrue(deliver > 0 && !dispatcher.substring(deliver).contains("permit("), "the publish itself (outside any transaction) never touches the gate");
        assertFalse(source("CommandPublishGate.java").contains("publish("), "the gate performs no broker call");
    }

    /**
     * Ledger P4-092: ingestion and dispatch add no unbounded in-memory queue. Telemetry is processed inside the single MQTT callback (no queue of
     * its own); the dispatcher's two pools are fed only after a free-worker permit is taken, so their internal queues can never grow past zero or
     * one entry per worker; nothing in the delivery, MQTT or telemetry packages creates an unbounded queue or an unbounded executor.
     */
    @Test
    void ingestionAndDispatchIntroduceNoUnboundedInMemoryQueue() throws IOException {
        Pattern unbounded = Pattern.compile("new\\s+(java\\.util\\.concurrent\\.)?(LinkedBlockingQueue|LinkedBlockingDeque|LinkedTransferQueue|PriorityBlockingQueue|ConcurrentLinkedQueue|ConcurrentLinkedDeque)\\s*<[^>]*>\\s*\\(\\s*\\)"
                + "|Executors\\.newCachedThreadPool|Executors\\.newWorkStealingPool|new\\s+ArrayDeque\\s*<[^>]*>\\s*\\(\\s*\\)");
        List<String> offenders = new java.util.ArrayList<>();
        for (Path f : sources(MAIN)) {
            String path = f.toString().replace('\\', '/');
            if (!(path.contains("/action/outbox/") || path.contains("/infrastructure/mqtt/") || path.contains("/telemetry/") || path.contains("/action/application/")))
                continue;
            if (unbounded.matcher(read(f)).find()) offenders.add(f.getFileName().toString());
        }
        assertTrue(offenders.isEmpty(), "unbounded queue or executor on the ingestion/dispatch path: " + offenders);

        String dispatcher = source("CommandOutboxDispatcher.java");
        int acquire = dispatcher.indexOf("freeWorkers.tryAcquire()");
        int execute = dispatcher.indexOf("workers.execute(");
        assertTrue(acquire > 0 && execute > acquire, "work is handed to the fixed pool only after a free-worker permit was taken");
        assertTrue(dispatcher.contains("new Semaphore(settings.workers())"), "the permits equal the pool size, so the pool's queue stays empty");
        assertFalse(read(MAIN.resolve("com/nexus/telemetry/application/TelemetryService.java")).contains("Queue<"), "telemetry processing keeps no queue");
    }
}
