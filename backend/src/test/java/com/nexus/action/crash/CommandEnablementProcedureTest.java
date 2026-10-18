package com.nexus.action.crash;

import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ledger P4-115: the documented enablement and rollback checklist is a script (phase4_enablement_check.py) that runs against a real, disposable
 * PostgreSQL, reads the checksums Flyway actually recorded, and FAILS when a prerequisite is missing. Each failure case is made on a throwaway
 * copy of a freshly migrated database; nothing outside the disposable container is touched, and the script itself only reads.
 */
@Tag("crash")
class CommandEnablementProcedureTest {
    private final CrashInfra infra = CrashInfra.get();
    private static final Path SCRIPT = Path.of("..", "phase4_enablement_check.py").toAbsolutePath().normalize();

    private String jdbcUrl(String database) {
        return "jdbc:postgresql://" + infra.postgres.getHost() + ":" + infra.postgres.getMappedPort(5432) + "/" + database;
    }

    private void execute(String database, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(database), infra.postgres.getUsername(), infra.postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** A freshly migrated database (all Flyway migrations of this repository), or a copy of one. */
    private String migratedDatabase() throws Exception {
        String name = "enable_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        execute(infra.postgres.getDatabaseName(), "CREATE DATABASE " + name);
        Flyway.configure().dataSource(jdbcUrl(name), infra.postgres.getUsername(), infra.postgres.getPassword()).locations("classpath:db/migration").load().migrate();
        return name;
    }

    /** Another freshly migrated database (a template copy is impossible: the TimescaleDB scheduler keeps a session on every database). */
    private String copyOf(String ignoredTemplate) throws Exception {
        return migratedDatabase();
    }

    private record Result(int exit, JsonNode report, String output) {}

    private Result run(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("python", SCRIPT.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the script finished");
        JsonNode report = output.trim().startsWith("{") ? CrashTestBase.JSON.readTree(output) : null;
        return new Result(process.exitValue(), report, output);
    }

    private String psql(String database) {
        return "docker exec -i " + infra.postgres.getContainerId() + " psql -U " + infra.postgres.getUsername() + " -d " + database;
    }

    private static String failing(Result r) {
        List<String> names = new ArrayList<>();
        if (r.report() != null) r.report().get("checks").forEach(c -> { if ("FAIL".equals(c.get("status").asText())) names.add(c.get("check").asText()); });
        return names.toString();
    }

    @Test
    @Timeout(600)
    void aFreshlyMigratedDatabasePassesTheDatabaseChecksWithTheChecksumsFlywayRecorded() throws Exception {
        assertTrue(Files.exists(SCRIPT), "the script is in the repository root: " + SCRIPT);
        String database = migratedDatabase();
        Result result = run("database", "--psql", psql(database));
        assertEquals(0, result.exit(), "a correctly migrated database passes: " + result.output());
        assertEquals("PASS", result.report().get("overall").asText());
        long checksumChecks = 0;
        for (JsonNode check : result.report().get("checks")) if (check.get("check").asText().contains("checksum") && "PASS".equals(check.get("status").asText())) checksumChecks++;
        assertEquals(4, checksumChecks, "V37 to V40 were each compared with the checksum Flyway stored: " + result.output());
    }

    @Test
    @Timeout(600)
    void everyMissingPrerequisiteFailsTheScriptByName() throws Exception {
        String good = migratedDatabase();

        String empty = "enable_empty_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        execute(infra.postgres.getDatabaseName(), "CREATE DATABASE " + empty);
        Result noMigrations = run("database", "--psql", psql(empty));
        assertEquals(1, noMigrations.exit(), "an unmigrated database fails: " + noMigrations.output());
        assertTrue(failing(noMigrations).contains("migration history readable"), failing(noMigrations));

        String missingTable = copyOf(good);
        execute(missingTable, "DROP TABLE inbound_message_dedupe");
        assertTrue(failing(run("database", "--psql", psql(missingTable))).contains("table inbound_message_dedupe"));

        String alteredChecksum = copyOf(good);
        execute(alteredChecksum, "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '39'");
        Result changed = run("database", "--psql", psql(alteredChecksum));
        assertEquals(1, changed.exit());
        assertTrue(failing(changed).contains("V39 applied with this repository's checksum"), failing(changed));

        String missingMigration = copyOf(good);
        execute(missingMigration, "DELETE FROM flyway_schema_history WHERE version = '40'");
        assertTrue(failing(run("database", "--psql", psql(missingMigration))).contains("V40 applied"));

        String backlog = copyOf(good);
        UUID command = UUID.randomUUID();
        execute(backlog, "INSERT INTO action_history(request_id, match_id, action_type, status, parameters, \"timestamp\") VALUES ('" + command + "', '" + UUID.randomUUID()
                + "', 'SEND_COMMAND', 'ACCEPTED_FOR_DISPATCH', '{}', now() - interval '1 hour')");
        execute(backlog, "INSERT INTO command_state_history(command_id, sequence, fact_type, to_state, attempt, reason_code, source, occurred_at)"
                + " VALUES ('" + command + "', 1, 'DELIVERY_UNKNOWN', 'DELIVERY_UNKNOWN', 0, 'LEGACY_DELIVERY_UNRECORDED', 'MIGRATION', now())");
        Result unreviewed = run("database", "--psql", psql(backlog));
        assertEquals(1, unreviewed.exit());
        assertTrue(failing(unreviewed).contains("no unreviewed DELIVERY_UNKNOWN backlog"), failing(unreviewed));
        assertEquals(0, run("database", "--psql", psql(backlog), "--max-delivery-unknown", "1").exit(), "declared as reviewed, the backlog no longer blocks");
    }

    @Test
    @Timeout(600)
    void theScriptIsReadOnlyAndTheRollbackCheckSeesUnresolvedObligations() throws Exception {
        String database = migratedDatabase();
        String before = fingerprint(database);
        run("database", "--psql", psql(database));
        run("rollback", "--psql", psql(database));
        assertEquals(before, fingerprint(database), "the checks changed nothing in the database");

        Result drained = run("rollback", "--psql", psql(database));
        assertEquals(0, drained.exit(), drained.output());

        // an obligation left unresolved fails the rollback check; building one needs the full row, so reuse the real table through an insert that satisfies V37
        UUID device = UUID.randomUUID(), site = UUID.randomUUID(), building = UUID.randomUUID(), space = UUID.randomUUID(), command = UUID.randomUUID();
        execute(database, "INSERT INTO sites(id,name) VALUES('" + site + "','E')");
        execute(database, "INSERT INTO buildings(id,site_id,name) VALUES('" + building + "','" + site + "','E')");
        execute(database, "INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES('" + space + "','" + building + "','E',now(),now())");
        execute(database, "INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode,created_at,updated_at,created_by,updated_by)"
                + " VALUES('" + device + "','" + space + "','E','CUSTOM','N','T','" + device + "','ACTIVE','SIMULATED',now(),now(),'t','t')");
        execute(database, "INSERT INTO action_history(request_id, match_id, device_id, action_type, status, parameters, \"timestamp\") VALUES ('" + command + "', '"
                + UUID.randomUUID() + "', '" + device + "', 'SEND_COMMAND', 'ACCEPTED_FOR_DISPATCH', '{}', now())");
        String payload = "{\"commandId\":\"" + command + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true,\"timestamp\":EPOCH}";
        execute(database, "DO $$ DECLARE p text := replace('" + payload.replace("'", "''") + "', 'EPOCH', floor(extract(epoch from now()))::bigint::text); BEGIN"
                + " INSERT INTO command_outbox(command_id, device_id, topic, payload_text, payload_sha256, payload_epoch_seconds, state, attempt_count, max_attempts,"
                + " next_attempt_at, expires_at, created_at, updated_at) VALUES ('" + command + "', '" + device + "', 'nexus/devices/" + device + "/commands', p,"
                + " encode(sha256(convert_to(p, 'UTF8')), 'hex'), floor(extract(epoch from now()))::bigint, 'QUEUED', 0, 3, now(), now() + interval '15 seconds', now(), now()); END $$");
        Result busy = run("rollback", "--psql", psql(database));
        assertEquals(1, busy.exit(), busy.output());
        assertTrue(failing(busy).contains("every obligation drained"), failing(busy));
    }

    private String fingerprint(String database) throws Exception {
        StringBuilder out = new StringBuilder();
        try (Connection c = DriverManager.getConnection(jdbcUrl(database), infra.postgres.getUsername(), infra.postgres.getPassword());
             Statement s = c.createStatement()) {
            for (String table : List.of("command_outbox", "command_state_history", "action_history", "inbound_message_dedupe", "flyway_schema_history")) {
                var rs = s.executeQuery("SELECT count(*)::text || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '') FROM " + table + " t");
                rs.next();
                out.append(table).append('=').append(rs.getString(1)).append(';');
            }
        }
        return out.toString();
    }

    @Test
    void thePostEnableCheckFailsWhenNothingIsListening() throws IOException, InterruptedException, Exception {
        Result result = run("post-enable", "--base-url", "http://127.0.0.1:1");
        assertEquals(1, result.exit(), result.output());
        assertTrue(failing(result).contains("queue metrics exposed"), failing(result));
    }
}
