package com.nexus.adapter.conformance;

import com.nexus.adapter.conformance.ConformanceReport.Outcome;
import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.AcknowledgementDecoder;
import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.AdapterDescriptor;
import com.nexus.adapter.spi.AdapterLifecycle;
import com.nexus.adapter.spi.Attempt;
import com.nexus.adapter.spi.AuthenticatedDevice;
import com.nexus.adapter.spi.CommandEgress;
import com.nexus.adapter.spi.CommandEnvelope;
import com.nexus.adapter.spi.ConnectivityObservation;
import com.nexus.adapter.spi.ConnectivitySource;
import com.nexus.adapter.spi.Decoded;
import com.nexus.adapter.spi.DeviceAuthenticator;
import com.nexus.adapter.spi.EvidenceKind;
import com.nexus.adapter.spi.RawMessage;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryDecoder;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.adapter.spi.TransportKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kit itself under test (ledger P5-103, P5-107). A kit that cannot fail proves nothing, so this class runs it against deliberately broken
 * adapters (mutants of a correct one) and requires the exact scenario to FAIL, and it requires that a missing capability is reported as
 * NOT_SUPPORTED and never as a pass, that a declared capability with no PASS is not conformant, and that every adapter is asked the same questions.
 */
class ConformanceKitTest {
    private static final Duration ONE = Duration.ofSeconds(1);

    /** A complete adapter whose every capability can be replaced by a broken one. The defaults delegate to the real MQTT and HTTP adapters. */
    static final class Mutant implements AdapterDescriptor, AdapterLifecycle, TelemetryDecoder, AcknowledgementDecoder, ConnectivitySource, CommandEgress, DeviceAuthenticator {
        final Subject mqtt = UnitSubjects.mqtt();
        final Subject http = UnitSubjects.http();
        Set<AdapterCapability> declared = EnumSet.allOf(AdapterCapability.class);
        String id = "mutant";
        Function<RawMessage, Decoded<TelemetryPayload>> telemetry = m -> ((TelemetryDecoder) mqtt.adapter()).decodeTelemetry(m);
        Function<RawMessage, Decoded<AckPayload>> ack = m -> ((AcknowledgementDecoder) mqtt.adapter()).decodeAcknowledgement(m);
        Function<RawMessage, Decoded<ConnectivityObservation>> connectivity = m -> ((ConnectivitySource) mqtt.adapter()).decodeConnectivity(m);
        Function<RawMessage, Decoded<AuthenticatedDevice>> auth = m -> ((DeviceAuthenticator) http.adapter()).authenticate(m);
        Map<String, Long> diagnostics = null;   // null: a counter that follows the deliveries
        final AtomicInteger deliveries = new AtomicInteger();      // calls to deliver()
        final AtomicInteger wireCalls = new AtomicInteger();       // times the wire was reached
        volatile Subject.EgressProbe.Mode mode = Subject.EgressProbe.Mode.CONFIRMED;
        BiFunction<CommandEnvelope, Duration, Attempt> egress = (c, t) -> switch (mode) {
            case CONFIRMED -> {
                wireCalls.incrementAndGet();
                yield Attempt.confirmed(EvidenceKind.BROKER_CONFIRMED);
            }
            case NOT_SENT -> Attempt.notSent("BROKER_DOWN");
            case UNCERTAIN -> {
                wireCalls.incrementAndGet();
                yield Attempt.uncertain("TIMEOUT");
            }
            case WIRE_FAILS_WITH_EXCEPTION -> {
                wireCalls.incrementAndGet();
                yield Attempt.uncertain("WIRE_FAILED");
            }
        };

        @Override public String id() { return id; }
        @Override public TransportKind transport() { return TransportKind.MQTT_JSON; }
        @Override public Set<Integer> supportedContractMajors() { return Set.of(1); }
        @Override public Set<AdapterCapability> capabilities() { return declared; }
        @Override public Map<String, Long> diagnostics() { return diagnostics != null ? diagnostics : Map.of("calls", (long) deliveries.get()); }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public Decoded<TelemetryPayload> decodeTelemetry(RawMessage message) { return telemetry.apply(message); }
        @Override public Decoded<AckPayload> decodeAcknowledgement(RawMessage message) { return ack.apply(message); }
        @Override public Decoded<ConnectivityObservation> decodeConnectivity(RawMessage message) { return connectivity.apply(message); }
        @Override public Decoded<AuthenticatedDevice> authenticate(RawMessage message) { return auth.apply(message); }
        @Override public Attempt deliver(CommandEnvelope command, Duration timeout) { deliveries.incrementAndGet(); return egress.apply(command, timeout); }
    }

    /** What the mutant's wire does when asked to reach it: counts calls and records bytes, with the result chosen by the egress lambda. */
    static Subject subject(Mutant m) {
        Subject.EgressProbe egress = new Subject.EgressProbe() {
            @Override
            public Optional<Delivery> deliver(Mode mode, UUID device, byte[] payload) {
                int before = m.wireCalls.get();
                m.mode = mode;
                Throwable thrown = null;
                Attempt attempt = null;
                try {
                    attempt = m.deliver(new CommandEnvelope(UUID.randomUUID(), device, payload, "h", 1L, "c"), ONE);
                } catch (Throwable t) {
                    thrown = t;
                }
                return Optional.of(new Delivery(attempt, m.wireCalls.get() - before, payload, thrown));
            }

            @Override public EvidenceKind expectedEvidence() { return EvidenceKind.BROKER_CONFIRMED; }
        };
        Subject.AuthProbe auth = m.http.auth().orElseThrow();
        return new UnitSubjects.SubjectImpl("mutant", Subject.Level.UNIT, m, m.mqtt.wire(), Optional.empty(), Optional.of(egress), Optional.of(auth), Map.of(),
                UnitSubjects.MQTT_REGISTERED);
    }

    private static ConformanceReport run(Mutant m) {
        return ConformanceKit.run(subject(m));
    }

    private static void assertFails(ConformanceReport report, String scenario) {
        assertEquals(Outcome.FAIL, report.scenario(scenario).outcome(), report.text());
        assertFalse(report.conforms(), "a failing scenario means the adapter does not conform");
    }

    @Test
    void theUnmodifiedCompleteAdapterConformsOnEveryDeclaredCapability() {
        Mutant m = new Mutant();
        ConformanceReport report = run(m);
        assertEquals(List.of(), report.failures(), report.text());
        assertTrue(report.conforms(), report.text());
        assertEquals(EnumSet.allOf(AdapterCapability.class).stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()), report.conformantCapabilities());
    }

    @Test
    void p5_103_everyAdapterIsAskedTheSameQuestionsAndOnlyTheDeclaredOnesAreExercised() {
        List<ConformanceReport> reports = List.of(ConformanceKit.run(UnitSubjects.mqtt()), ConformanceKit.run(UnitSubjects.http()), ConformanceKit.run(UnitSubjects.simulator()),
                ConformanceKit.run(UnitSubjects.serial()));
        List<String> ids = ConformanceKit.scenarioIds();
        assertEquals(ids.size(), new HashSet<>(ids).size(), "scenario ids are unique");
        assertTrue(ids.size() >= 30, "the kit has real breadth: " + ids.size());
        for (ConformanceReport report : reports) {
            assertEquals(ids, report.results().stream().map(ConformanceReport.Result::scenario).toList(), "same scenarios, same order, for " + report.subject());
            for (ConformanceReport.Result result : report.results()) {
                boolean capabilityScenario = !result.capability().equals("DESCRIPTOR") && !result.capability().equals("INGEST");
                if (capabilityScenario && !report.declared().contains(AdapterCapability.valueOf(result.capability()))) {
                    assertEquals(Outcome.NOT_SUPPORTED, result.outcome(), report.subject() + " " + result.scenario() + ": an undeclared capability is never exercised");
                } else if (capabilityScenario) {
                    assertNotEquals(Outcome.NOT_SUPPORTED, result.outcome(), report.subject() + " " + result.scenario() + ": a declared capability is always exercised or explained");
                }
            }
        }
    }

    @Test
    void p5_107_aMissingCapabilityIsNotSupportedNeverAPassAndTheAdapterIsNeverCalledForIt() {
        Mutant m = new Mutant();
        m.declared = EnumSet.of(AdapterCapability.LIFECYCLE, AdapterCapability.TELEMETRY_DECODER);   // implements everything, declares two
        ConformanceReport report = run(m);
        assertFails(report, "descriptor.declared-capabilities-equal-implemented-interfaces");   // implemented but not declared is itself a defect
        assertEquals(0, m.deliveries.get(), "the egress was never called because it was not declared");
        for (String id : new String[]{"egress.confirmed-states-its-evidence-and-sends-the-stored-bytes", "authenticator.credential-shapes", "acknowledgement.shared-cases", "connectivity.shared-cases"}) {
            assertEquals(Outcome.NOT_SUPPORTED, report.scenario(id).outcome(), id);
        }
        assertFalse(report.conformantCapabilities().contains("COMMAND_EGRESS"));
        assertFalse(java.util.regex.Pattern.compile("COMMAND_EGRESS\s+PASS").matcher(report.text()).find());
        assertTrue(java.util.regex.Pattern.compile("COMMAND_EGRESS\s+NOT_SUPPORTED").matcher(report.text()).find());

        // an ingest-only adapter that declares exactly what it implements conforms as ingest-only, and the report says so
        ConformanceReport honest = ConformanceKit.run(UnitSubjects.http());
        assertTrue(honest.conforms());
        assertEquals(Outcome.NOT_SUPPORTED, honest.scenario("egress.doubt-is-uncertain").outcome());
    }

    @Test
    void p5_107_aDeclaredCapabilityWithoutAPassIsNotConformantAndNotApplicableIsNotAPass() {
        Mutant m = new Mutant();
        Subject base = subject(m);
        Map<String, String> excuses = new java.util.HashMap<>();
        for (String id : ConformanceKit.scenarioIds()) if (id.startsWith("egress.")) excuses.put(id, "pretend it does not apply");
        Subject evasive = new UnitSubjects.SubjectImpl("evasive", Subject.Level.UNIT, m, base.wire(), Optional.empty(), base.egress(), base.auth(), excuses, UnitSubjects.MQTT_REGISTERED);
        ConformanceReport report = ConformanceKit.run(evasive);
        assertEquals(List.of(), report.failures());
        assertEquals(Outcome.NOT_APPLICABLE, report.scenario("egress.doubt-is-uncertain").outcome());
        assertFalse(report.conformantCapabilities().contains("COMMAND_EGRESS"), "excusing every scenario does not earn the capability");
        assertFalse(report.conforms(), "COMMAND_EGRESS is declared and has no PASS: not conformant");
    }

    // ------------------------------------------------------------------------------------------------ mutants: the kit must FAIL them

    @Test
    void aDecoderThatAcceptsAnythingFails() {
        Mutant m = new Mutant();
        TelemetryPayload any = new TelemetryPayload(SharedCases.DEVICE, Instant.parse("2026-01-01T00:00:00Z"), "S", 1.0, "U", null, null, null);
        m.telemetry = message -> Decoded.accepted(any);
        ConformanceReport report = run(m);
        assertFails(report, "telemetry.shared-cases");
        assertFails(report, "telemetry.decoded-values-are-typed-and-deterministic");
        assertFails(report, "telemetry.hostile-input-is-refused-never-thrown");
    }

    @Test
    void aDecoderThatThrowsOnHostileInputFails() {
        Mutant m = new Mutant();
        var real = m.telemetry;
        m.telemetry = message -> {
            if (message.payload().length > 100_000) throw new IllegalStateException("boom");
            return real.apply(message);
        };
        assertFails(run(m), "telemetry.hostile-input-is-refused-never-thrown");
    }

    @Test
    void aConnectivityDecoderThatIgnoresTheAddressedDeviceFails() {
        Mutant m = new Mutant();
        m.connectivity = message -> Decoded.accepted(new ConnectivityObservation(UUID.randomUUID(), ConnectivityObservation.State.ONLINE));
        assertFails(run(m), "connectivity.observation-names-the-addressed-device");
    }

    @Test
    void anAckDecoderThatDropsTheReasonFails() {
        Mutant m = new Mutant();
        var real = m.ack;
        m.ack = message -> real.apply(message) instanceof Decoded.Accepted<AckPayload> a
                ? Decoded.accepted(new AckPayload(a.value().commandId(), a.value().status(), a.value().deviceTimestamp(), null)) : real.apply(message);
        assertFails(run(m), "acknowledgement.decoded-values-are-typed");
    }

    @Test
    void anAuthenticatorThatAcceptsAWrongSecretOrDistinguishesFailuresFails() {
        Mutant lax = new Mutant();
        var real = lax.auth;
        lax.auth = message -> {
            String header = message.metadata().get("authorization");
            if (header != null && header.startsWith("Bearer ") && header.length() > 20) return Decoded.accepted(new AuthenticatedDevice(UUID.randomUUID(), "x"));
            return real.apply(message);
        };
        assertFails(run(lax), "authenticator.credential-shapes");
        assertFails(run(lax), "authenticator.hostile-credentials-are-refused-never-thrown");

        Mutant oracle = new Mutant();
        var strict = oracle.auth;
        oracle.auth = message -> strict.apply(message) instanceof Decoded.Refused<AuthenticatedDevice> r && message.metadata().get("authorization") == null
                ? Decoded.refused(RejectionCode.FORBIDDEN_DEVICE) : strict.apply(message);
        assertFails(run(oracle), "authenticator.credential-shapes");
    }

    @Test
    void anEgressThatMisclassifiesFails() {
        Mutant wrongEvidence = new Mutant();
        wrongEvidence.egress = (c, t) -> Attempt.confirmed(EvidenceKind.HTTP_SERVED);
        assertFails(run(wrongEvidence), "egress.confirmed-states-its-evidence-and-sends-the-stored-bytes");

        Mutant alwaysConfirmed = new Mutant();   // the doubt and the proof cases both come back CONFIRMED
        alwaysConfirmed.egress = (c, t) -> {
            alwaysConfirmed.wireCalls.incrementAndGet();
            return Attempt.confirmed(EvidenceKind.BROKER_CONFIRMED);
        };
        assertFails(run(alwaysConfirmed), "egress.not-sent-only-with-proof-and-a-stable-code");
        assertFails(run(alwaysConfirmed), "egress.doubt-is-uncertain");

        Mutant retries = new Mutant();
        retries.egress = (c, t) -> {
            retries.wireCalls.addAndGet(2);   // a second wire attempt inside one call
            return Attempt.confirmed(EvidenceKind.BROKER_CONFIRMED);
        };
        assertFails(run(retries), "egress.at-most-one-wire-attempt-per-call");

        Mutant throwing = new Mutant();
        throwing.egress = (c, t) -> {
            if (throwing.mode == Subject.EgressProbe.Mode.WIRE_FAILS_WITH_EXCEPTION) throw new IllegalStateException("wire down");
            return Attempt.uncertain("TIMEOUT");
        };
        assertFails(run(throwing), "egress.a-wire-exception-is-never-confirmed-and-never-propagates");

        Mutant frozenCounters = new Mutant();   // its counters never move whatever it delivers
        frozenCounters.diagnostics = Map.of("calls", 5L);
        assertFails(run(frozenCounters), "egress.counters-follow-the-outcomes");
    }

    @Test
    void aDescriptorThatLiesOrLeaksFails() {
        Mutant badId = new Mutant();
        badId.id = "Not A Stable ID";
        assertFails(run(badId), "descriptor.id-is-stable-lower-case");

        Mutant negative = new Mutant();
        negative.diagnostics = Map.of("calls", -1L);
        assertFails(run(negative), "descriptor.diagnostics-are-counters-only");

        Mutant secretCounter = new Mutant();
        secretCounter.diagnostics = Map.of("lastDeviceToken", 1L);
        assertFails(run(secretCounter), "descriptor.diagnostics-are-counters-only");

        Mutant liar = new Mutant();
        liar.declared = EnumSet.of(AdapterCapability.LIFECYCLE, AdapterCapability.TELEMETRY_DECODER, AdapterCapability.COMMAND_EGRESS);   // implements more than it declares
        assertFails(run(liar), "descriptor.declared-capabilities-equal-implemented-interfaces");
        assertFails(run(liar), "descriptor.registers-through-the-normal-registry");
    }

    @Test
    void theReportNeverContainsPayloadContentOrIdentifiers() {
        String text = ConformanceKit.run(UnitSubjects.mqtt()).text() + ConformanceKit.run(UnitSubjects.http()).text();
        assertFalse(text.contains(SharedCases.DEVICE.toString()));
        assertFalse(text.contains("nxk1_"));
        assertFalse(text.contains("deviceId"));
    }
}
