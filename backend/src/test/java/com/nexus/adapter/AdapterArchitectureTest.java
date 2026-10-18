package com.nexus.adapter;

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
 * The mechanical form of exit criterion E1 (adapter-contract.md section 3; ledger P5-027 to P5-030 and P5-142). These are source-level
 * proofs, like the Phase 4 architecture tests: they read the production sources and fail the build on a forbidden dependency. Each rule is
 * also exercised on synthetic sources so that a rule that matches nothing today cannot silently stop working.
 */
class AdapterArchitectureTest {
    private static final Path MAIN = locate("src/main/java/com/nexus");

    /** Transport packages: nothing outside adapter may depend on them, and they may not depend on the core. */
    private static final List<String> TRANSPORTS = List.of("mqtt", "http", "simulator");
    /** The core services and persistence a transport package must never import. */
    private static final List<Pattern> CORE_SERVICES = List.of(
            Pattern.compile("com\\.nexus\\.(twin|rule|telemetry\\.application|device\\.(application|command|connectivity|capability|binding|onboarding)|realtime|infrastructure)\\b"),
            Pattern.compile("com\\.nexus\\.action\\.(application|outbox|domain|persistence)\\b"),
            Pattern.compile("\\bimport\\s+[\\w.]*\\.\\w*Repository;"),
            Pattern.compile("org\\.springframework\\.jdbc\\b"), Pattern.compile("\\bJdbcTemplate\\b"), Pattern.compile("jakarta\\.persistence|javax\\.persistence|org\\.hibernate"));
    private static final Pattern ADAPTER_SPI = Pattern.compile("com\\.nexus\\.adapter\\.spi\\b");

    private static Path locate(String relative) {
        Path p = Path.of(relative);
        if (!Files.exists(p)) p = Path.of("backend").resolve(relative);
        return p.normalize();
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Strips comments so a sentence in Javadoc is not mistaken for a dependency. */
    static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    static boolean dependsOnTransport(String source) {
        return Pattern.compile("com\\.nexus\\.adapter\\.(" + String.join("|", TRANSPORTS) + ")\\b").matcher(code(source)).find();
    }

    static boolean importsCoreService(String source) {
        String c = code(source);
        return CORE_SERVICES.stream().anyMatch(p -> p.matcher(c).find());
    }

    private static Set<String> names(List<Path> files) {
        return files.stream().map(f -> MAIN.relativize(f).toString().replace('\\', '/')).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void p5_027_noTwinRuleActionOrTelemetryClassDependsOnATransportPackage() throws IOException {
        for (String area : List.of("twin", "rule", "action", "telemetry")) {
            for (Path file : javaFiles(MAIN.resolve(area))) {
                assertFalse(dependsOnTransport(read(file)), area + " must not depend on an adapter transport package: " + file);
            }
        }
        // The rule is live: it flags a synthetic violation and ignores a comment.
        assertTrue(dependsOnTransport("package com.nexus.twin;\nimport com.nexus.adapter.mqtt.MqttJsonAdapter;"));
        assertTrue(dependsOnTransport("package com.nexus.rule;\nclass X { com.nexus.adapter.http.HttpAdapter a; }"));
        assertFalse(dependsOnTransport("package com.nexus.twin;\n// see com.nexus.adapter.mqtt for details\n/* com.nexus.adapter.http */"));
        assertFalse(dependsOnTransport("package com.nexus.twin;\nimport com.nexus.adapter.spi.TransportEligibility;"));
    }

    @Test
    void p5_027_noTwinOrRuleClassReferencesAnyAdapterPackageAtAll() throws IOException {
        for (String area : List.of("twin", "rule")) {
            for (Path file : javaFiles(MAIN.resolve(area))) {
                assertFalse(code(read(file)).contains("com.nexus.adapter"), "the " + area + " engine must not know about adapters: " + file);
            }
        }
    }

    @Test
    void p5_028_noTransportPackageDependsOnTheCoreServices() throws IOException {
        for (String transport : TRANSPORTS) {
            for (Path file : javaFiles(MAIN.resolve("adapter").resolve(transport))) {
                assertFalse(importsCoreService(read(file)), "adapter." + transport + " must hold transport code only: " + file);
            }
        }
        assertTrue(importsCoreService("import com.nexus.twin.application.TwinService;"));
        assertTrue(importsCoreService("import com.nexus.action.outbox.OutboxDeliveryOperations;"));
        assertTrue(importsCoreService("import com.nexus.telemetry.application.TelemetryService;"));
        assertTrue(importsCoreService("import com.nexus.device.domain.DeviceRepository;"));
        assertTrue(importsCoreService("import org.springframework.jdbc.core.JdbcTemplate;"));
        assertFalse(importsCoreService("import com.nexus.adapter.spi.Decoded;\nimport java.util.UUID;"));
    }

    @Test
    void p5_029_onlyAdapterCoreBridgesBothSides() throws IOException {
        Set<String> bridging = new TreeSet<>();
        for (Path file : javaFiles(MAIN.resolve("adapter"))) {
            String source = read(file);
            if (ADAPTER_SPI.matcher(code(source)).find() && importsCoreService(source)) bridging.add(MAIN.relativize(file).toString().replace('\\', '/'));
        }
        assertFalse(bridging.isEmpty(), "adapter.core bridges the spi to the core services");
        for (String name : bridging) assertTrue(name.startsWith("adapter/core/"), "only adapter.core may reference both sides: " + name);
        // Outside adapter/ only two classes touch the spi: the acceptance guard (the eligibility port) and the MQTT listener (the decoder's rejection codes).
        Set<String> outsideAdapter = new TreeSet<>();
        for (Path file : javaFiles(MAIN)) {
            String relative = MAIN.relativize(file).toString().replace('\\', '/');
            if (!relative.startsWith("adapter/") && ADAPTER_SPI.matcher(code(read(file))).find()) outsideAdapter.add(relative);
        }
        assertEquals(Set.of("action/application/executor/CommandExecutor.java", "infrastructure/mqtt/TelemetryMQTTListener.java"), outsideAdapter);
    }

    @Test
    void p5_030_theSpiHasNoFrameworkOrPersistenceDependency() throws IOException {
        List<String> forbidden = List.of("org.springframework", "jakarta.persistence", "javax.persistence", "jakarta.servlet", "javax.servlet", "java.sql", "javax.sql",
                "org.hibernate", "com.fasterxml", "org.eclipse.paho", "io.micrometer", "com.zaxxer", "org.slf4j", "com.nexus.");
        List<Path> files = javaFiles(MAIN.resolve("adapter/spi"));
        assertTrue(files.size() >= 25, "the spi package is present: " + files.size());
        for (Path file : files) {
            String c = code(read(file));
            for (String prefix : forbidden) {
                if (prefix.equals("com.nexus.")) {
                    assertFalse(Pattern.compile("com\\.nexus\\.(?!adapter\\.spi)").matcher(c).find(), file + " imports a core package");
                } else {
                    assertFalse(c.contains(prefix), file + " must not use " + prefix);
                }
            }
        }
    }

    @Test
    void p5_142_commandAcceptanceAsksTheTransportEligibilityPortAndTheMqttRuleIsUnchanged() throws IOException {
        String executor = read(MAIN.resolve("action/application/executor/CommandExecutor.java"));
        String prepare = executor.substring(executor.indexOf("public PreparedCommand prepare("), executor.indexOf("public ActionExecutionResult publishPrepared("));
        assertTrue(code(prepare).contains("transportEligibility().eligible("), "the acceptance guard calls the port");
        assertFalse(code(prepare).contains("deviceConnectivityRegistry"), "the acceptance guard no longer reads the MQTT registry itself");
        assertFalse(code(prepare).contains("isConnected()"), "the acceptance guard no longer reads the broker client itself");
        String rule = read(MAIN.resolve("adapter/core/MqttTransportEligibility.java"));
        assertTrue(rule.contains("\"Device is offline\"") && rule.contains("\"MQTT backend is not connected\""), "the MQTT wording is unchanged");
        assertTrue(rule.indexOf("DeviceConnectivityStatus.ONLINE") < rule.indexOf("isConnected()"), "registry first, then the broker client (the former order)");
        assertTrue(read(MAIN.resolve("adapter/core/TransportEligibilityRouter.java")).contains("NO_ADAPTER"), "a transport without a rule fails closed");
    }

    @Test
    void p5_r31_nothingButTheEgressRouterReachesAnAdaptersCommandEgressAndNothingCallsTheRouterYet() throws IOException {
        // Contract R31: no command can reach the broker around the outbox. In P5.3 the adapter's egress (and the bridge to the bounded publisher)
        // is reachable only through CommandEgressRouter, and no production class calls the router: the dispatcher joins in P5.4, where this test is
        // tightened to "only the dispatcher".
        Set<String> routerUsers = new TreeSet<>();
        Set<String> egressCallers = new TreeSet<>();
        for (Path file : javaFiles(MAIN)) {
            String c = code(read(file));
            String relative = MAIN.relativize(file).toString().replace('\\', '/');
            if (Pattern.compile("\\bCommandEgressRouter\\b").matcher(c).find()) routerUsers.add(relative);
            if (c.contains(".deliver(")) egressCallers.add(relative);
        }
        assertEquals(Set.of("adapter/core/CommandEgressRouter.java"), routerUsers, "no production class uses the egress router yet");
        assertEquals(Set.of("adapter/core/CommandEgressRouter.java"), egressCallers, "only the router calls an adapter's deliver()");
    }

    @Test
    void theCoreDoesNotImportTransportSpecificLibrariesIntoTheSpiCapabilities() throws IOException {
        // No capability interface may force an adapter to implement a no-op: every spi interface has only the methods of its capability.
        for (String name : List.of("TelemetryDecoder", "AcknowledgementDecoder", "ConnectivitySource", "CommandEgress", "DeviceAuthenticator", "AdapterLifecycle")) {
            String c = code(read(MAIN.resolve("adapter/spi/" + name + ".java")));
            long methods = Pattern.compile("\\);").matcher(c).results().count();
            assertTrue(methods >= 1 && methods <= 2, name + " declares " + methods + " methods");
            assertFalse(c.contains(" default "), name + " has no default (no-op) method");
        }
    }
}
