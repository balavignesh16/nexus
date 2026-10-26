package com.nexus.adapter.conformance;

import com.nexus.adapter.conformance.ConformanceReport.Outcome;
import com.nexus.adapter.core.AdapterRegistry;
import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.TransportKind;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conformance kit run at the UNIT level against the real adapter classes (no database, no broker) and against the test-only fourth adapter.
 * The LIVE run on the running application is {@code AdapterConformanceIntegrationTest}. Each test asserts the capability matrix the adapter
 * ACTUALLY has: nothing here assumes the three transports are alike.
 */
class AdapterConformanceTest {
    private static void show(ConformanceReport report) {
        System.out.println(report.text());
    }

    private static void assertNoFailures(ConformanceReport report) {
        assertEquals(java.util.List.of(), report.failures(), report.text());
    }

    @Test
    void mqttJsonConformsForEveryCapabilityItDeclares() {
        ConformanceReport report = ConformanceKit.run(UnitSubjects.mqtt());
        show(report);
        assertNoFailures(report);
        assertEquals(EnumSet.allOf(AdapterCapability.class).stream().filter(c -> c != AdapterCapability.DEVICE_AUTHENTICATOR).collect(java.util.stream.Collectors.toSet()), report.declared(),
                "MQTT declares everything except a device authenticator (scoping is by topic at the broker)");
        assertTrue(report.conforms(), report.text());
        assertEquals(Outcome.NOT_SUPPORTED, report.scenario("authenticator.credential-shapes").outcome());
        assertEquals(Outcome.PASS, report.scenario("egress.not-sent-only-with-proof-and-a-stable-code").outcome());
        assertEquals(Outcome.PASS, report.scenario("telemetry.device-identity-comes-from-the-transport").outcome(), "the topic names the device");
    }

    @Test
    void httpWebhookConformsAndHasNoCommandEgress() {
        ConformanceReport report = ConformanceKit.run(UnitSubjects.http());
        show(report);
        assertNoFailures(report);
        assertTrue(report.conforms(), report.text());
        assertEquals(Set.of(AdapterCapability.LIFECYCLE, AdapterCapability.TELEMETRY_DECODER, AdapterCapability.ACKNOWLEDGEMENT_DECODER, AdapterCapability.DEVICE_AUTHENTICATOR),
                report.declared(), "ingest and authentication only: HTTP command pull is deferred until its delivery contract is proven");
        for (String scenario : new String[]{"egress.confirmed-states-its-evidence-and-sends-the-stored-bytes", "egress.doubt-is-uncertain", "egress.counters-follow-the-outcomes"}) {
            assertEquals(Outcome.NOT_SUPPORTED, report.scenario(scenario).outcome(), scenario);
        }
        assertEquals(Outcome.NOT_SUPPORTED, report.scenario("connectivity.shared-cases").outcome(), "HTTP status is a controller input, not a connectivity source");
        assertFalse(report.conformantCapabilities().contains("COMMAND_EGRESS"));
        assertEquals(Outcome.NOT_APPLICABLE, report.scenario("telemetry.device-identity-comes-from-the-transport").outcome(), "no topic in HTTP: identity is the authenticated credential");
    }

    @Test
    void theSimulatorConformsForItsCommandEgressAndHasNoDecoders() {
        ConformanceReport report = ConformanceKit.run(UnitSubjects.simulator());
        show(report);
        assertNoFailures(report);
        assertTrue(report.conforms(), report.text());
        assertEquals(Set.of(AdapterCapability.LIFECYCLE, AdapterCapability.COMMAND_EGRESS), report.declared());
        assertEquals(Outcome.NOT_SUPPORTED, report.scenario("telemetry.shared-cases").outcome(), "the simulator hands typed objects to the core: nothing to decode");
        assertEquals(Outcome.PASS, report.scenario("egress.confirmed-states-its-evidence-and-sends-the-stored-bytes").outcome());
        assertEquals(Outcome.PASS, report.scenario("egress.doubt-is-uncertain").outcome(), "a device that fails after being called is UNCERTAIN");
        assertEquals(Outcome.PASS, report.scenario("egress.not-sent-only-with-proof-and-a-stable-code").outcome());
    }

    @Test
    void theTestOnlySerialAdapterConformsAsAnIngestOnlyTransport() {
        ConformanceReport report = ConformanceKit.run(UnitSubjects.serial());
        show(report);
        assertNoFailures(report);
        assertTrue(report.conforms(), report.text());
        assertEquals(Set.of(AdapterCapability.LIFECYCLE, AdapterCapability.TELEMETRY_DECODER), report.declared());
        assertEquals(Outcome.NOT_SUPPORTED, report.scenario("egress.doubt-is-uncertain").outcome(), "no command path: reported, never passed");
        assertEquals(Outcome.PASS, report.scenario("ingest.valid-telemetry-reaches-the-core-exactly-once").outcome());
        assertEquals(Outcome.PASS, report.scenario("ingest.invalid-telemetry-never-reaches-the-core").outcome());
    }

    @Test
    void theRealAdaptersAndTheTestAdapterShareOneRegistryContract() {
        // each registers alone through the normal registry; the two that share the SIMULATOR slot cannot coexist, which is the registry's rule
        for (Subject subject : new Subject[]{UnitSubjects.mqtt(), UnitSubjects.http(), UnitSubjects.simulator(), UnitSubjects.serial()}) {
            AdapterRegistry registry = new AdapterRegistry(java.util.List.of(subject.adapter()));
            assertEquals(1, registry.reports().size(), subject.name());
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new AdapterRegistry(java.util.List.of(UnitSubjects.simulator().adapter(), UnitSubjects.serial().adapter())),
                "two adapters on one transport are refused: the test adapter can never silently replace the simulator");
        assertEquals(TransportKind.SIMULATOR, UnitSubjects.serial().adapter().transport());
    }
}
