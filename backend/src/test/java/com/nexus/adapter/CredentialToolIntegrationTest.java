package com.nexus.adapter;

import com.nexus.adapter.credentials.CredentialAdmin;
import com.nexus.adapter.credentials.CredentialCli;
import com.nexus.adapter.http.KeyFormat;
import com.nexus.adapter.http.KeyHasher;
import com.nexus.contract.PythonOracle;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The credential-administration tool on a real PostgreSQL (ledger P5-054, P5-060, P5-061, P5-145 to P5-148): one-time key display and file
 * delivery, the least-privilege database roles, one transaction per operation with an audit event, and the pepper (fail closed; the tool and the
 * backend compute the same value, checked against an independent Python HMAC).
 */
@Testcontainers
class CredentialToolIntegrationTest {
    static final String PEPPER = "tool-test-pepper-0123456789abcdef";

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    @TempDir
    Path temp;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).load().migrate();
    }

    static Connection owner() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    static UUID device() throws SQLException {
        UUID site = UUID.randomUUID(), building = UUID.randomUUID(), space = UUID.randomUUID(), device = UUID.randomUUID();
        try (Connection c = owner(); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO sites(id,name) VALUES ('" + site + "','S')");
            s.executeUpdate("INSERT INTO buildings(id,site_id,name) VALUES ('" + building + "','" + site + "','B')");
            s.executeUpdate("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES ('" + space + "','" + building + "','Sp',NOW(),NOW())");
            s.executeUpdate("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode) VALUES ('"
                    + device + "','" + space + "','D','CUSTOM','NEXUS','M1','SN-" + device + "','ACTIVE','SIMULATED')");
        }
        return device;
    }

    static long count(String sql) throws SQLException {
        try (Connection c = owner(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    static Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("NEXUS_ADMIN_DB_URL", db.getJdbcUrl());
        env.put("NEXUS_ADMIN_DB_USER", db.getUsername());
        env.put("NEXUS_ADMIN_DB_PASSWORD", db.getPassword());
        env.put(KeyHasher.PEPPER_ENV, PEPPER);
        return env;
    }

    record Run(int exit, String out, String err) {}

    static Run cli(Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        int exit = CredentialCli.run(args, env, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), "tester");
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    static String keyIn(String text) {
        for (String line : text.split("\\R")) if (line.startsWith(KeyFormat.PREFIX)) return line.strip();
        throw new AssertionError("no key in output");
    }

    static CredentialAdmin admin() {
        return new CredentialAdmin(TransportSchemaDirect::open, new KeyHasher(PEPPER.getBytes(StandardCharsets.UTF_8)), new SecureRandom(), "tester");
    }

    /** The owner connection as a method reference target. */
    static final class TransportSchemaDirect {
        static Connection open() throws SQLException {
            return owner();
        }
    }

    // ------------------------------------------------------------------ P5-145: the secret is handled safely

    @Test
    void p5_145_theKeyIsShownOnceAndOnlyThere() throws Exception {
        UUID device = device();
        Run created = cli(env(), "create", "--device", device.toString());
        assertEquals(0, created.exit(), created.err());
        String key = keyIn(created.out());
        assertEquals(KeyFormat.KEY_LENGTH, key.length());
        assertEquals(1, created.out().split(java.util.regex.Pattern.quote(key), -1).length - 1, "the key appears exactly once in the output");
        assertFalse(created.err().contains(key));
        String secret = KeyFormat.parse(key).orElseThrow().secret();
        for (String later : new String[]{"list", "audit"}) {
            Run run = cli(env(), later, "--device", device.toString());
            assertEquals(0, run.exit(), run.err());
            assertFalse(run.out().contains(secret) || run.err().contains(secret), later + " must never show the secret");
            assertFalse(run.out().contains(key));
        }
        assertFalse(env().containsValue(key), "the key is never placed in the environment");
        assertTrue(env().values().stream().noneMatch(v -> v.contains(secret)));
    }

    @Test
    void p5_145_anOutputFileIsNewOwnerOnlyHoldsTheKeyAndIsNeverOverwritten() throws Exception {
        UUID device = device();
        Path file = temp.resolve("device.key");
        Run created = cli(env(), "create", "--device", device.toString(), "--output-file", file.toString());
        assertEquals(0, created.exit(), created.err());
        String key = Files.readString(file).strip();
        assertEquals(KeyFormat.KEY_LENGTH, key.length());
        assertFalse(created.out().contains(key) || created.err().contains(key), "with a file the key is not printed");
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
        } else {
            List<AclEntry> acl = Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl();
            assertEquals(1, acl.size(), "owner-only ACL");
            assertEquals(Files.getOwner(file), acl.get(0).principal());
        }
        // a second credential cannot be written over it, and the failed attempt creates nothing
        UUID other = device();
        long before = count("SELECT COUNT(*) FROM device_ingestion_credentials WHERE device_id = '" + other + "'");
        Run refused = cli(env(), "create", "--device", other.toString(), "--output-file", file.toString());
        assertEquals(1, refused.exit());
        assertEquals(before, count("SELECT COUNT(*) FROM device_ingestion_credentials WHERE device_id = '" + other + "'"), "no credential whose key could not be delivered");
        assertEquals(key, Files.readString(file).strip(), "the existing file is untouched");
        assertFalse(refused.out().contains(key) || refused.err().contains(key));
    }

    @Test
    void p5_054_theDatabaseNeverHoldsThePlaintextKeyOrSecret() throws Exception {
        UUID device = device();
        String key = keyIn(cli(env(), "create", "--device", device.toString()).out());
        String secret = KeyFormat.parse(key).orElseThrow().secret();
        List<String> tables = new java.util.ArrayList<>();
        try (Connection c = owner(); var s = c.createStatement(); var r = s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")) {
            while (r.next()) tables.add(r.getString(1));
        }
        assertTrue(tables.size() > 40);
        for (String table : tables) {
            if (table.startsWith("_timescaledb") || table.equals("flyway_schema_history")) continue;
            assertEquals(0, count("SELECT COUNT(*) FROM \"" + table + "\" t WHERE t::text LIKE '%" + secret + "%' OR t::text LIKE '%" + key + "%'"), table);
        }
        byte[] stored;
        try (Connection c = owner(); var s = c.createStatement(); var r = s.executeQuery("SELECT secret_hash FROM device_ingestion_credentials WHERE lookup_id = '" + KeyFormat.parse(key).orElseThrow().lookupId() + "'")) {
            r.next();
            stored = r.getBytes(1);
        }
        assertEquals(32, stored.length);
        assertNotEquals(secret, new String(stored, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ rotation and revocation at the database level

    @Test
    void p5_060_p5_061_rotationKeepsTheOldKeyUntilItsInstantAndRevocationIsFinal() throws Exception {
        UUID device = device();
        CredentialAdmin admin = admin();
        CredentialAdmin.Issued first = admin.create(device);
        assertThrows(CredentialAdmin.AdminException.class, () -> admin.create(device), "one ACTIVE credential per device");
        CredentialAdmin.Issued second = admin.rotate(device, Duration.ofHours(2));
        assertNotEquals(first.lookupId(), second.lookupId());
        List<CredentialAdmin.Row> rows = admin.list(device);
        assertEquals(List.of("RETIRING", "ACTIVE"), rows.stream().map(CredentialAdmin.Row::status).toList());
        assertTrue(rows.get(0).retireAt() != null, "the retiring credential has its instant");
        assertThrows(CredentialAdmin.AdminException.class, () -> admin.rotate(device, Duration.ofHours(2)), "no second rotation while one is retiring");
        assertThrows(CredentialAdmin.AdminException.class, () -> admin.rotate(device, Duration.ofSeconds(5)), "the retire window is bounded below");
        assertThrows(CredentialAdmin.AdminException.class, () -> admin.rotate(device, Duration.ofDays(8)), "and above");
        admin.revoke(first.lookupId());
        assertThrows(CredentialAdmin.AdminException.class, () -> admin.revoke(first.lookupId()), "revoking twice is refused: it is final");
        admin.revoke(second.lookupId());
        assertEquals(List.of("REVOKED", "REVOKED"), admin.list(device).stream().map(CredentialAdmin.Row::status).toList());
        CredentialAdmin.Issued third = admin.create(device);
        assertNotEquals(first.lookupId(), third.lookupId());
        assertEquals("REVOKED", admin.list(device).get(0).status(), "a new credential does not revive an old one");
        assertThrows(CredentialAdmin.AdminException.class, () -> admin.create(UUID.randomUUID()), "an unknown device");
    }

    // ------------------------------------------------------------------ P5-147: audit and atomicity

    @Test
    void p5_147_everyOperationAppendsAnAuditEventWithoutSecretsAndAFailureLeavesNothing() throws Exception {
        UUID device = device();
        CredentialAdmin admin = admin();
        CredentialAdmin.Issued first = admin.create(device);
        admin.rotate(device, Duration.ofHours(1));
        admin.revoke(first.lookupId());
        List<CredentialAdmin.Event> events = admin.audit(device);
        assertEquals(List.of("CREATED", "RETIRING", "ROTATED_IN", "REVOKED"), events.stream().map(CredentialAdmin.Event::eventType).toList());
        events.forEach(e -> assertEquals("cli:tester", e.actor()));
        assertEquals(0, count("SELECT COUNT(*) FROM device_credential_events WHERE actor !~ '^cli:[A-Za-z0-9._-]+$'"), "no free text, no secret in the actor");

        // a failure at the audit step (injected into the connection) rolls the whole operation back and prints no key
        UUID other = device();
        CredentialAdmin.ConnectionSource failing = () -> failingOnEvents(owner());
        CredentialAdmin broken = new CredentialAdmin(failing, new KeyHasher(PEPPER.getBytes(StandardCharsets.UTF_8)), new SecureRandom(), "tester");
        CredentialAdmin.AdminException failure = assertThrows(CredentialAdmin.AdminException.class, () -> broken.create(other));
        assertFalse(failure.getMessage().matches(".*nxk1_.*"));
        assertEquals(0, count("SELECT COUNT(*) FROM device_ingestion_credentials WHERE device_id = '" + other + "'"), "no credential survives a failed create");
        assertEquals(0, count("SELECT COUNT(*) FROM device_credential_events e JOIN device_ingestion_credentials c USING (credential_id) WHERE c.device_id = '" + other + "'"));
        admin.create(other);
        CredentialAdmin brokenRotate = new CredentialAdmin(failing, new KeyHasher(PEPPER.getBytes(StandardCharsets.UTF_8)), new SecureRandom(), "tester");
        assertThrows(CredentialAdmin.AdminException.class, () -> brokenRotate.rotate(other, Duration.ofHours(1)));
        assertEquals(List.of("ACTIVE"), admin.list(other).stream().map(CredentialAdmin.Row::status).toList(), "a failed rotation leaves the old credential ACTIVE and creates no second one");
    }

    private static Connection failingOnEvents(Connection real) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("prepareStatement") && args[0].toString().contains("device_credential_events") && args[0].toString().startsWith("INSERT")) {
                throw new SQLException("injected failure");
            }
            try {
                return method.invoke(real, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(CredentialToolIntegrationTest.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }

    // ------------------------------------------------------------------ P5-146: least privilege

    @Test
    void p5_146_theCredentialAdminRoleCanDoItsJobAndNothingElse() throws Exception {
        String script = Files.readString(Path.of(System.getProperty("user.dir")).resolve("src/main/resources/db/operator/credential-roles.sql"));
        String backendRole = "backend_under_test";
        try (Connection c = owner(); var s = c.createStatement()) {
            s.executeUpdate("CREATE ROLE " + backendRole + " LOGIN PASSWORD 'backend-pw'");
            s.executeUpdate("GRANT USAGE ON SCHEMA public TO " + backendRole);
            s.executeUpdate("GRANT SELECT ON devices TO " + backendRole);
            for (String statement : script.replace("{{ADMIN_PASSWORD}}", "admin-pw").replace("{{BACKEND_ROLE}}", backendRole).split(";\\s*\\R")) {
                String sql = statement.lines().filter(l -> !l.stripLeading().startsWith("--")).reduce("", (a, b) -> a + b + "\n").strip();
                if (!sql.isEmpty()) s.executeUpdate(sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql);
            }
        }
        UUID device = device();
        Map<String, String> adminEnv = env();
        adminEnv.put("NEXUS_ADMIN_DB_USER", "nexus_credential_admin");
        adminEnv.put("NEXUS_ADMIN_DB_PASSWORD", "admin-pw");
        Run created = cli(adminEnv, "create", "--device", device.toString());
        assertEquals(0, created.exit(), created.err());
        String lookup = KeyFormat.parse(keyIn(created.out())).orElseThrow().lookupId();
        assertEquals(0, cli(adminEnv, "list", "--device", device.toString()).exit());
        assertEquals(0, cli(adminEnv, "rotate", "--device", device.toString()).exit());
        assertEquals(0, cli(adminEnv, "revoke", "--credential", lookup).exit());
        assertEquals(0, cli(adminEnv, "audit", "--device", device.toString()).exit());

        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), "nexus_credential_admin", "admin-pw"); var s = c.createStatement()) {
            for (String forbidden : new String[]{"SELECT COUNT(*) FROM telemetry_history", "SELECT COUNT(*) FROM command_outbox", "SELECT COUNT(*) FROM rules",
                    "SELECT COUNT(*) FROM action_history", "DELETE FROM device_ingestion_credentials", "DELETE FROM device_credential_events",
                    "UPDATE devices SET name = 'x'", "INSERT INTO sites(id,name) VALUES (gen_random_uuid(),'x')", "UPDATE device_credential_events SET actor = 'x'",
                    "TRUNCATE device_ingestion_credentials", "ALTER TABLE devices ADD COLUMN zzz INT", "CREATE TABLE zzz(a INT)"}) {
                SQLException denied = assertThrows(SQLException.class, () -> s.execute(forbidden), forbidden);
                assertTrue(denied.getMessage().contains("permission denied") || denied.getMessage().contains("must be owner"), forbidden + " -> " + denied.getMessage());
            }
        }
        // the backend role may read credentials and update only the last-contact column
        UUID other = device();
        CredentialAdmin.Issued issued = admin().create(other);
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), backendRole, "backend-pw"); var s = c.createStatement()) {
            assertEquals(1, s.executeUpdate("UPDATE device_ingestion_credentials SET last_valid_contact_at = now() WHERE lookup_id = '" + issued.lookupId() + "'"));
            try (var r = s.executeQuery("SELECT status FROM device_ingestion_credentials WHERE lookup_id = '" + issued.lookupId() + "'")) {
                assertTrue(r.next());
            }
            for (String forbidden : new String[]{"UPDATE device_ingestion_credentials SET status = 'REVOKED'", "UPDATE device_ingestion_credentials SET secret_hash = secret_hash",
                    "DELETE FROM device_ingestion_credentials", "INSERT INTO device_credential_events(event_id,credential_id,event_type,occurred_at,actor) VALUES (gen_random_uuid(), gen_random_uuid(),'CREATED',now(),'x')",
                    "SELECT * FROM device_credential_events"}) {
                SQLException denied = assertThrows(SQLException.class, () -> s.execute(forbidden), forbidden);
                assertTrue(denied.getMessage().contains("permission denied"), forbidden + " -> " + denied.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ P5-148: the pepper

    @Test
    void p5_148_aMissingPepperStopsTheToolAndChangesNothing() throws Exception {
        UUID device = device();
        Map<String, String> noPepper = env();
        noPepper.remove(KeyHasher.PEPPER_ENV);
        Run refused = cli(noPepper, "create", "--device", device.toString());
        assertEquals(1, refused.exit());
        assertTrue(refused.err().contains("pepper"));
        assertFalse(refused.err().contains(PEPPER) || refused.out().contains(PEPPER));
        assertEquals(0, count("SELECT COUNT(*) FROM device_ingestion_credentials WHERE device_id = '" + device + "'"));
        Map<String, String> tooShort = env();
        tooShort.put(KeyHasher.PEPPER_ENV, "short");
        assertEquals(1, cli(tooShort, "create", "--device", device.toString()).exit());
        Path pepperFile = temp.resolve("pepper.txt");
        Files.writeString(pepperFile, PEPPER + "\n");
        Map<String, String> fromFile = env();
        fromFile.remove(KeyHasher.PEPPER_ENV);
        fromFile.put(KeyHasher.PEPPER_FILE_ENV, pepperFile.toString());
        assertEquals(0, cli(fromFile, "create", "--device", device.toString()).exit(), "a pepper file works like the variable");
        assertEquals(2, cli(env()).exit(), "no operation is a usage error");
        assertEquals(2, cli(env(), "create").exit());
        assertEquals(2, cli(env(), "bogus", "--device", device.toString()).exit());
        assertEquals(2, cli(env(), "create", "--device", "not-a-uuid").exit());
    }

    @Test
    void p5_148_theHasherAgreesWithAnIndependentHmacAndADifferentPepperInvalidatesEverything() throws Exception {
        KeyHasher hasher = new KeyHasher(PEPPER.getBytes(StandardCharsets.UTF_8));
        SecureRandom random = new SecureRandom();
        StringBuilder vectors = new StringBuilder();
        List<String> secrets = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) secrets.add(KeyFormat.newSecret(random));
        secrets.add("");
        secrets.add("A".repeat(43));
        String program = "import hmac, hashlib; pepper = b'" + PEPPER + "'\nfor s in " + secrets.stream().map(s -> "'" + s + "'").toList()
                + ":\n    print(hmac.new(pepper, s.encode('utf-8'), hashlib.sha256).hexdigest())";
        String[] expected = PythonOracle.run(program).strip().split("\\R");
        assertEquals(secrets.size(), expected.length);
        for (int i = 0; i < secrets.size(); i++) {
            assertEquals(expected[i], java.util.HexFormat.of().formatHex(hasher.hash(secrets.get(i))), "independent HMAC-SHA-256 for vector " + i);
            assertTrue(hasher.matches(hasher.hash(secrets.get(i)), secrets.get(i)));
        }
        KeyHasher other = new KeyHasher("a-completely-different-pepper-value".getBytes(StandardCharsets.UTF_8));
        assertFalse(other.matches(hasher.hash(secrets.get(0)), secrets.get(0)), "changing the pepper invalidates every credential");
        assertFalse(hasher.matches(new byte[32], secrets.get(0)));
        assertThrows(IllegalStateException.class, () -> new KeyHasher(new byte[8]));
        assertThrows(IllegalStateException.class, () -> KeyHasher.fromEnvironment(Map.of()));
        Set<String> lookups = new java.util.HashSet<>();
        for (int i = 0; i < 2000; i++) assertTrue(lookups.add(KeyFormat.newLookupId(random)), "lookup ids are unique random values");
        assertTrue(KeyFormat.parse(KeyFormat.format(KeyFormat.newLookupId(random), KeyFormat.newSecret(random))).isPresent());
        for (String bad : new String[]{"", "nxk1_", "nxk1_" + "a".repeat(24) + "_" + "A".repeat(42), "nxk2_" + "a".repeat(24) + "_" + "A".repeat(43),
                "nxk1_" + "G".repeat(24) + "_" + "A".repeat(43), "nxk1_" + "a".repeat(24) + "-" + "A".repeat(43), "nxk1_" + "a".repeat(24) + "_" + "A".repeat(42) + "!"}) {
            assertTrue(KeyFormat.parse(bad).isEmpty(), bad);
        }
    }
}
