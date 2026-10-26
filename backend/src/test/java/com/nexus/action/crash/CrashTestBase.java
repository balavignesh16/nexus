package com.nexus.action.crash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Base of the crash-recovery campaign (ledger P4-059 to P4-067, P4-093, P4-107, P4-112). Every scenario runs one or more REAL backend
 * processes (the application class with the production wiring, a fresh JVM each) against disposable PostgreSQL and Mosquitto, stops them
 * with {@code Runtime.halt} or {@code destroyForcibly} (no graceful shutdown), starts new ones and then asserts on durable rows, on the
 * broker's own count of what it received, and on the read-only lifecycle API. No scenario touches the development database or broker.
 */
abstract class CrashTestBase {
    protected static final ObjectMapper JSON = new ObjectMapper();
    protected final CrashInfra infra = CrashInfra.get();
    private final List<Node> nodes = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void healthyInfrastructure() {
        infra.dbProxy.restore();
        infra.brokerProxy.restore();
    }

    @AfterEach
    void stopEverything() {
        for (Node node : nodes) node.kill();
        nodes.clear();
        infra.dbProxy.restore();
        infra.brokerProxy.restore();
    }

    // ------------------------------------------------------------------------------------------ devices

    /** A device with an LED WRITE capability and ONLINE connectivity, written directly so every backend process hydrates it at startup. */
    protected UUID device() {
        UUID site = UUID.randomUUID(), building = UUID.randomUUID(), space = UUID.randomUUID(), device = UUID.randomUUID();
        var jdbc = infra.jdbc;
        jdbc.update("INSERT INTO sites(id,name) VALUES(?,'CRASH')", site);
        jdbc.update("INSERT INTO buildings(id,site_id,name) VALUES(?,?,'CRASH')", building, site);
        jdbc.update("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES(?,?,'CRASH',now(),now())", space, building);
        jdbc.update("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode,created_at,updated_at,created_by,updated_by)"
                + " VALUES(?,?,'CRASH','CUSTOM','NEXUS','TEST',?,'ACTIVE','SIMULATED',now(),now(),'test','test')", device, space, device.toString());
        jdbc.update("INSERT INTO device_capabilities(id,device_id,capability_type,direction,unit,metadata) VALUES(?,?,'LED','WRITE','BOOLEAN','{}')", UUID.randomUUID(), device);
        jdbc.update("INSERT INTO device_connectivity(device_id,status,last_changed_at) VALUES(?,'ONLINE',now())", device);
        return device;
    }

    // ------------------------------------------------------------------------------------------ nodes

    /** The properties every node needs; {@code overrides} replace or add. The schema is created by the first node that starts. */
    protected Node start(Map<String, String> overrides) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("spring.datasource.url", "jdbc:postgresql://127.0.0.1:" + infra.dbProxy.port() + "/" + infra.postgres.getDatabaseName());
        p.put("spring.datasource.username", infra.postgres.getUsername());
        p.put("spring.datasource.password", infra.postgres.getPassword());
        p.put("spring.datasource.hikari.connection-timeout", "5000");
        p.put("spring.jpa.show-sql", "false");
        p.put("mqtt.broker.url", "tcp://127.0.0.1:" + infra.brokerProxy.port());
        p.put("mqtt.client.id", "crash-" + UUID.randomUUID());
        p.put("server.port", "0");
        p.put("nexus.command.outbox.enabled", "true");
        p.put("nexus.command.outbox.dispatcher-enabled", "true");
        p.put("nexus.command.lifecycle-scanners-enabled", "true");     // the test resources switch the scanners off; a real node runs them
        p.put("nexus.command.outbox.lease-duration", "PT4S");
        p.put("nexus.command.outbox.attempt-timeout", "PT2S");
        p.put("nexus.command.outbox.delivery-window", "PT20S");
        p.put("nexus.command.ack-timeout", "PT30S");
        p.put("nexus.command.ack-timeout-scan-interval", "PT1S");
        p.put("nexus.command.flow.global-queue-cap", "100000");
        p.put("nexus.command.flow.device-queue-cap", "1000");
        p.put("nexus.command.flow.publish-rate-per-second", "1000");
        p.put("nexus.command.trace-projection-interval", "PT1S");
        p.put("logging.level.org.hibernate.SQL", "OFF");
        p.put("crash.kill", "NONE");
        p.putAll(overrides);
        Node node = new Node(p);
        nodes.add(node);
        node.awaitReady();
        return node;
    }

    protected Node start() { return start(Map.of()); }

    protected final class Node {
        private final Process process;
        private final Path log;
        private final CompletableFuture<Integer> ready = new CompletableFuture<>();
        private volatile boolean halted;
        int port;

        private Node(Map<String, String> props) {
            try {
                Path dir = Path.of("target", "crash-logs");
                Files.createDirectories(dir);
                log = dir.resolve("node-" + System.nanoTime() + ".log");
                String java = ProcessHandle.current().info().command().orElse("java");
                List<String> cmd = new ArrayList<>(List.of(java, "-Xmx384m", "-Xshare:off", "-XX:+EnableDynamicAgentLoading"));
                for (var e : props.entrySet()) cmd.add("-D" + e.getKey() + "=" + e.getValue());
                String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
                cmd.addAll(List.of("-cp", classpath, "crashnode.CrashNode"));
                ProcessBuilder builder = new ProcessBuilder(cmd).redirectErrorStream(true);
                process = builder.start();
            } catch (IOException e) {
                throw new IllegalStateException("backend process could not start", e);
            }
            Thread pump = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                     var out = Files.newBufferedWriter(log)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        out.write(line);
                        out.newLine();
                        out.flush();
                        if (line.startsWith("CRASHNODE_READY port=")) ready.complete(Integer.parseInt(line.substring("CRASHNODE_READY port=".length()).trim()));
                        if (line.startsWith("CRASHNODE_HALT")) halted = true;
                    }
                } catch (IOException ignored) {
                    // the process ended
                } finally {
                    ready.completeExceptionally(new IllegalStateException("backend process ended before it was ready; log " + log));
                }
            }, "crash-node-output");
            pump.setDaemon(true);
            pump.start();
        }

        private void awaitReady() {
            try {
                port = ready.get(240, TimeUnit.SECONDS);
            } catch (Exception e) {
                kill();
                throw new IllegalStateException("backend process did not become ready: " + e + " (log " + log + ")", e);
            }
        }

        boolean alive() { return process.isAlive(); }

        boolean halted() { return halted; }

        /** Waits for the process to end by itself (a kill hook) and returns its exit code. */
        int awaitExit(Duration timeout) throws InterruptedException {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new AssertionError("the backend process did not stop at its kill point; log " + log);
            return process.exitValue();
        }

        /** SIGKILL equivalent: no shutdown hook runs. */
        void kill() {
            if (process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        /** POST /devices/{id}/commands; returns the HTTP status, or -1 when the connection ended without a response (the process died). */
        int sendLed(UUID device, boolean value) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/devices/" + device + "/commands"))
                        .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":" + value + "}")).build();
                return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (IOException e) {
                return -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        /** The same request, returning the parsed command id of an accepted command. */
        UUID sendLedAndGetId(UUID device, boolean value) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/devices/" + device + "/commands"))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":" + value + "}")).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new AssertionError("command refused: HTTP " + response.statusCode() + " " + response.body());
            JsonNode body = JSON.readTree(response.body());
            if (!"ACCEPTED_FOR_DISPATCH".equals(body.path("status").asText()) && !"SUCCESS".equals(body.path("status").asText()))
                throw new AssertionError("command not accepted: " + response.body());
            return UUID.fromString(body.get("requestId").asText());
        }

        JsonNode get(String path) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new AssertionError("GET " + path + " -> HTTP " + response.statusCode());
            return JSON.readTree(response.body());
        }

        Path log() { return log; }
    }

    // ------------------------------------------------------------------------------------------ assertions' helpers

    protected static void await(BooleanSupplier condition, String what, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(100);
        }
    }

    protected String outboxState(UUID id) {
        var rows = infra.jdbc.queryForList("SELECT state FROM command_outbox WHERE command_id = ?", String.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    protected List<String> facts(UUID id) {
        return infra.jdbc.queryForList("SELECT fact_type FROM command_state_history WHERE command_id = ? ORDER BY sequence", String.class, id);
    }

    /** The newest command accepted for a device (the kill hooks may stop the process before the HTTP response is written). */
    protected UUID latestCommand(UUID device) throws InterruptedException {
        await(() -> !infra.jdbc.queryForList("SELECT request_id FROM action_history WHERE device_id = ?", UUID.class, device).isEmpty(), "the command to be accepted", 60);
        return infra.jdbc.queryForObject("SELECT request_id FROM action_history WHERE device_id = ? ORDER BY \"timestamp\" DESC LIMIT 1", UUID.class, device);
    }

    /** Every history sequence of every command is gapless and unique (the primary key guarantees uniqueness; this guards the gaplessness). */
    protected void assertGaplessHistory(UUID id) {
        List<Integer> sequences = infra.jdbc.queryForList("SELECT sequence FROM command_state_history WHERE command_id = ? ORDER BY sequence", Integer.class, id);
        for (int i = 0; i < sequences.size(); i++)
            if (sequences.get(i) != i + 1) throw new AssertionError("history of " + id + " has a gap or a repeat: " + sequences);
    }
}
