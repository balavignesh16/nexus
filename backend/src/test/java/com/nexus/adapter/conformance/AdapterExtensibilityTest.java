package com.nexus.adapter.conformance;

import com.nexus.adapter.AdapterArchitectureTest;
import com.nexus.adapter.core.AdapterRegistry;
import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.TelemetryDecoder;
import com.nexus.adapter.spi.TelemetryIntake;
import com.nexus.adapter.spi.TransportKind;
import com.nexus.contract.PythonOracle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ledger P5-108, the extensibility proof, as reproducible evidence rather than a statement: a fourth transport (the test-only serial adapter)
 * is added, registered through the normal registry and judged by the same conformance kit, while
 * <ul>
 *   <li>the source of the test adapter obeys the transport dependency rules (no Twin, Rule, core service or persistence import);</li>
 *   <li>no production class knows the test adapter, and nothing in production can select it;</li>
 *   <li>the Digital Twin, Rule Engine, telemetry service and every Phase 4 reliability class (outbox, retry, deadlines, ordering, ACK handling,
 *       queue capacity, rate limiting) are byte-identical to the Phase 4 baseline, except for the short, named list of approved Phase 5 changes.</li>
 * </ul>
 * The last two use {@code git diff} against the validated Phase 4 commit, so they hold for the whole of Phase 5 and not only for this change.
 */
class AdapterExtensibilityTest {
    /** The validated Phase 4 baseline every Phase 5 diff is measured from. */
    static final String PHASE4_BASELINE = "e29e215e5138de4460e9891e05859a61a098c3aa";

    /** The only core files Phase 5 may change outside {@code adapter/}: each is an approved, tested extension point (adapter-contract.md sections 5 and 9). */
    static final Set<String> APPROVED_CORE_CHANGES = Set.of(
            "backend/src/main/java/com/nexus/action/application/executor/CommandExecutor.java",              // P5.2: asks the transport-eligibility port
            "backend/src/main/java/com/nexus/device/command/DeviceCommandAckMessageHandler.java",             // P5.2: acknowledge() entry point
            "backend/src/main/java/com/nexus/device/connectivity/DeviceStatusMessageHandler.java",            // P5.2: observe() entry point
            "backend/src/main/java/com/nexus/infrastructure/mqtt/TelemetryMQTTListener.java",                 // P5.3: decoder extraction and transport guard
            "backend/src/main/resources/db/migration/V41__device_transport_and_ingestion_credentials.sql",     // V41
            "backend/src/main/resources/db/operator/credential-roles.sql");                                   // P5.4: least-privilege roles

    private static final Path ROOT = repositoryRoot();

    private static Path repositoryRoot() {
        return PythonOracle.repositoryRoot();
    }

    private static String read(String relative) throws IOException {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
    }

    private static List<Path> javaFiles(String relative) throws IOException {
        try (Stream<Path> s = Files.walk(ROOT.resolve(relative))) {
            return s.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }

    private static List<String> git(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", ROOT.toString()));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int status = process.waitFor();
        assertEquals(0, status, "git " + String.join(" ", args) + " failed (the extensibility proof needs git and the Phase 4 baseline commit): " + output);
        return output.lines().filter(l -> !l.isBlank()).toList();
    }

    // ------------------------------------------------------------------------------------------------ the test adapter is test code only

    @Test
    void p5_108_theFourthAdapterIsTestCodeThatObeysTheTransportDependencyRules() throws IOException {
        String path = "backend/src/test/java/com/nexus/adapter/conformance/SerialLikeAdapter.java";
        String source = read(path);
        assertFalse(AdapterArchitectureTest.importsCoreService(source), "the test adapter reaches the core only through the SPI ports");
        String code = AdapterArchitectureTest.code(source);
        assertTrue(code.contains("TelemetryIntake"), "it feeds the core through the telemetry port");
        for (String forbidden : new String[]{"@Component", "@Service", "@Configuration", "@Bean", "@RestController", "@Controller", "SpringApplication"}) {
            assertFalse(code.contains(forbidden), "the test adapter is not a Spring bean: " + forbidden);
        }
        assertFalse(Files.exists(ROOT.resolve("backend/src/main/java/com/nexus/adapter/conformance")), "no production package of that name");
        assertTrue(Stream.of(SerialLikeAdapter.class.getConstructors()).allMatch(c ->
                        Arrays.stream(c.getParameterTypes()).allMatch(t -> t == TelemetryIntake.class || t == java.time.Clock.class)),
                "its only collaborators are the telemetry port and a clock");
    }

    @Test
    void p5_108_noProductionClassKnowsTheTestAdapterOrTheKit() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles("backend/src/main/java")) {
            String code = AdapterArchitectureTest.code(Files.readString(file, StandardCharsets.UTF_8));
            if (code.contains("SerialLikeAdapter") || code.contains("serial-test") || code.contains("adapter.conformance") || code.contains("ConformanceKit")) {
                offenders.add(ROOT.relativize(file).toString());
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void p5_108_theTestTransportCannotBeAssignedToADevice() {
        for (String candidate : new String[]{"SERIAL_TEST", "SERIAL", "serial-test", "serial_test", "simulator", "mqtt_json", "", " SIMULATOR", "SIMULATOR "}) {
            assertTrue(TransportKind.parse(candidate).isEmpty(), "'" + candidate + "' is not a transport");
        }
        assertEquals(Set.of("MQTT_JSON", "HTTP_WEBHOOK", "SIMULATOR"), Arrays.stream(TransportKind.values()).map(Enum::name).collect(Collectors.toSet()),
                "adding a real transport means adding one value here and widening the V41 CHECK constraint: an SPI and schema change, never a Twin or Rule change");
        assertTrue(Files.exists(ROOT.resolve("backend/src/main/resources/db/migration/V41__device_transport_and_ingestion_credentials.sql")));
    }

    @Test
    void p5_108_theTestAdapterRegistersThroughTheNormalRegistryAndIsJudgedByTheSameKit() {
        Subject subject = UnitSubjects.serial();
        AdapterRegistry registry = new AdapterRegistry(List.of(subject.adapter()));
        assertEquals(1, registry.adapters().size());
        assertTrue(registry.capability(TransportKind.SIMULATOR, TelemetryDecoder.class).isPresent());
        assertFalse(registry.capability(TransportKind.SIMULATOR, com.nexus.adapter.spi.CommandEgress.class).isPresent(), "it has no command path, and the registry says so");
        assertTrue(registry.reports().get(0).ingestOnly());
        assertEquals(Set.of(AdapterCapability.LIFECYCLE, AdapterCapability.TELEMETRY_DECODER), registry.reports().get(0).capabilities());
        ConformanceReport report = ConformanceKit.run(subject);
        assertTrue(report.conforms(), report.text());
        assertEquals(ConformanceReport.Outcome.PASS, report.scenario("telemetry.shared-cases").outcome(), "the very table that judges MQTT and HTTP");
    }

    // ------------------------------------------------------------------------------------------------ the core did not change

    @Test
    void p5_108_theTwinRuleTelemetryAndPhase4ReliabilityPackagesAreIdenticalToThePhase4Baseline() throws Exception {
        List<String> untouched = List.of("backend/src/main/java/com/nexus/twin", "backend/src/main/java/com/nexus/rule", "backend/src/main/java/com/nexus/telemetry",
                "backend/src/main/java/com/nexus/action/outbox", "backend/src/main/java/com/nexus/action/lifecycle", "backend/src/main/java/com/nexus/action/domain",
                "backend/src/main/java/com/nexus/action/persistence", "backend/src/main/java/com/nexus/realtime");
        for (String directory : untouched) {
            assertTrue(Files.isDirectory(ROOT.resolve(directory)), directory + " exists (otherwise the proof proves nothing)");
            List<String> committed = git("diff", "--name-status", PHASE4_BASELINE, "--", directory);
            assertEquals(List.of(), committed, directory + " differs from the Phase 4 baseline in the working tree");
            List<String> untracked = git("status", "--porcelain", "--untracked-files=all", "--", directory);
            assertEquals(List.of(), untracked, directory + " has uncommitted or untracked changes");
        }
    }

    @Test
    void p5_108_everyPhase5ChangeOutsideTheAdapterPackagesIsOnTheApprovedList() throws Exception {
        List<String> changed = git("diff", "--name-only", PHASE4_BASELINE, "--", "backend/src/main");
        List<String> untracked = git("status", "--porcelain", "--untracked-files=all", "--", "backend/src/main").stream().map(l -> l.substring(3).trim()).toList();
        Set<String> outside = new TreeSet<>();
        for (String file : Stream.concat(changed.stream(), untracked.stream()).toList()) {
            if (!file.startsWith("backend/src/main/java/com/nexus/adapter/")) outside.add(file);
        }
        assertEquals(new TreeSet<>(APPROVED_CORE_CHANGES), outside, "Phase 5 changed these core files; each must be an approved, tested extension point");
    }

    @Test
    void p5_108_theSpiNamesNoTransportImplementationAndNoCorePackage() throws IOException {
        for (Path file : javaFiles("backend/src/main/java/com/nexus/adapter/spi")) {
            String code = AdapterArchitectureTest.code(Files.readString(file, StandardCharsets.UTF_8));
            assertFalse(AdapterArchitectureTest.dependsOnTransport(code), file.getFileName() + " depends on a transport implementation");
            assertFalse(AdapterArchitectureTest.importsCoreService(code), file.getFileName() + " imports a core service");
        }
    }
}
