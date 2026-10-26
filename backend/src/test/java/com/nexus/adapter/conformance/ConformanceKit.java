package com.nexus.adapter.conformance;

import com.nexus.adapter.conformance.ConformanceReport.Outcome;
import com.nexus.adapter.conformance.ConformanceReport.Result;
import com.nexus.adapter.conformance.SharedCases.Case;
import com.nexus.adapter.conformance.Subject.EgressProbe;
import com.nexus.adapter.conformance.Subject.IngestProbe;
import com.nexus.adapter.core.AdapterRegistry;
import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.AcknowledgementDecoder;
import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.AdapterDescriptor;
import com.nexus.adapter.spi.AdapterLifecycle;
import com.nexus.adapter.spi.Attempt;
import com.nexus.adapter.spi.AuthenticatedDevice;
import com.nexus.adapter.spi.ConnectivityObservation;
import com.nexus.adapter.spi.ConnectivitySource;
import com.nexus.adapter.spi.Decoded;
import com.nexus.adapter.spi.DeviceAuthenticator;
import com.nexus.adapter.spi.RawMessage;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryDecoder;
import com.nexus.adapter.spi.TelemetryPayload;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The reusable, capability-aware conformance kit (P5.7). It takes a {@link Subject}, reads the adapter's DECLARED capabilities from its
 * descriptor, and runs the scenarios of exactly those capabilities against it:
 * <ul>
 *   <li>a scenario of a capability the adapter does not declare is reported NOT_SUPPORTED and nothing is exercised;</li>
 *   <li>a scenario that cannot apply to a transport is NOT_APPLICABLE with its reason (the subject must state it; the kit never decides);</li>
 *   <li>everything else is PASS or FAIL, and an exception inside a scenario is a FAIL (its class name, never its message, is reported).</li>
 * </ul>
 * The kit reaches only the SPI and the subject's probes. It contains no adapter-specific rule: every transport is judged by the same shared table,
 * the same limits and the same classification rules.
 */
public final class ConformanceKit {
    private static final Pattern ADAPTER_ID = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    private static final Pattern COUNTER_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,63}");
    private static final Set<String> FORBIDDEN_IN_COUNTER_NAMES = Set.of("secret", "token", "password", "hash", "pepper", "credential", "payload", "key");
    /** A scenario body returns this prefix to PASS with an explanatory note (used only for registered differences). */
    private static final String NOTE = "NOTE: ";
    private static final Pattern STABLE_CODE = Pattern.compile("[A-Z0-9_]{1,64}");

    /** A scenario body: returns null for PASS, a message for FAIL; throws {@link NotApplicable} when the requirement cannot apply here. */
    @FunctionalInterface
    private interface Body {
        String run(Subject subject) throws Exception;
    }

    private static final class NotApplicable extends RuntimeException {
        NotApplicable(String reason) {
            super(reason, null, false, false);
        }
    }

    private record Scenario(String id, String capability, Body body) {}

    private ConformanceKit() {}

    /** The ids of every scenario, in the order they are reported. */
    public static List<String> scenarioIds() {
        return scenarios().stream().map(Scenario::id).toList();
    }

    public static ConformanceReport run(Subject subject) {
        AdapterDescriptor adapter = subject.adapter();
        Set<AdapterCapability> declared = Set.copyOf(adapter.capabilities());
        List<Result> results = new ArrayList<>();
        for (Scenario scenario : scenarios()) {
            boolean always = scenario.capability.equals("DESCRIPTOR") || scenario.capability.equals("INGEST");
            if (!always && !declared.contains(AdapterCapability.valueOf(scenario.capability))) {
                results.add(new Result(scenario.id, scenario.capability, Outcome.NOT_SUPPORTED, "capability not declared; nothing exercised"));
                continue;
            }
            String reason = subject.notApplicable().get(scenario.id);
            if (reason != null) {
                results.add(new Result(scenario.id, scenario.capability, Outcome.NOT_APPLICABLE, reason));
                continue;
            }
            try {
                String failure = scenario.body.run(subject);
                if (failure == null) results.add(new Result(scenario.id, scenario.capability, Outcome.PASS, ""));
                else if (failure.startsWith(NOTE)) results.add(new Result(scenario.id, scenario.capability, Outcome.PASS, failure.substring(NOTE.length())));
                else results.add(new Result(scenario.id, scenario.capability, Outcome.FAIL, failure));
            } catch (NotApplicable e) {
                results.add(new Result(scenario.id, scenario.capability, Outcome.NOT_APPLICABLE, e.getMessage()));
            } catch (Throwable t) {
                results.add(new Result(scenario.id, scenario.capability, Outcome.FAIL, "threw " + t.getClass().getSimpleName()));
            }
        }
        return new ConformanceReport(subject.name(), subject.level().name(), declared, results);
    }

    // ------------------------------------------------------------------------------------------------ the scenarios

    private static List<Scenario> scenarios() {
        List<Scenario> list = new ArrayList<>();
        // descriptor, registry, lifecycle
        list.add(new Scenario("descriptor.id-is-stable-lower-case", "DESCRIPTOR", ConformanceKit::descriptorId));
        list.add(new Scenario("descriptor.transport-and-contract-majors", "DESCRIPTOR", ConformanceKit::descriptorTransport));
        list.add(new Scenario("descriptor.declared-capabilities-equal-implemented-interfaces", "DESCRIPTOR", ConformanceKit::descriptorCapabilities));
        list.add(new Scenario("descriptor.registers-through-the-normal-registry", "DESCRIPTOR", ConformanceKit::descriptorRegisters));
        list.add(new Scenario("descriptor.diagnostics-are-counters-only", "DESCRIPTOR", ConformanceKit::descriptorDiagnostics));
        list.add(new Scenario("lifecycle.start-and-stop-are-idempotent", "LIFECYCLE", ConformanceKit::lifecycle));
        // telemetry
        list.add(new Scenario("telemetry.shared-cases", "TELEMETRY_DECODER", s -> sharedCases(s, SharedCases.telemetry())));
        list.add(new Scenario("telemetry.hostile-input-is-refused-never-thrown", "TELEMETRY_DECODER", s -> hostile(s, SharedCases.Kind.TELEMETRY)));
        list.add(new Scenario("telemetry.decoded-values-are-typed-and-deterministic", "TELEMETRY_DECODER", ConformanceKit::telemetryValues));
        list.add(new Scenario("telemetry.device-identity-comes-from-the-transport", "TELEMETRY_DECODER", ConformanceKit::telemetryIdentity));
        list.add(new Scenario("telemetry.rejections-carry-no-content", "TELEMETRY_DECODER", ConformanceKit::rejectionsCarryNoContent));
        // acknowledgements
        list.add(new Scenario("acknowledgement.shared-cases", "ACKNOWLEDGEMENT_DECODER", s -> sharedCases(s, SharedCases.acknowledgements())));
        list.add(new Scenario("acknowledgement.hostile-input-is-refused-never-thrown", "ACKNOWLEDGEMENT_DECODER", s -> hostile(s, SharedCases.Kind.ACKNOWLEDGEMENT)));
        list.add(new Scenario("acknowledgement.decoded-values-are-typed", "ACKNOWLEDGEMENT_DECODER", ConformanceKit::ackValues));
        // connectivity
        list.add(new Scenario("connectivity.shared-cases", "CONNECTIVITY_SOURCE", s -> sharedCases(s, SharedCases.statuses())));
        list.add(new Scenario("connectivity.hostile-input-is-refused-never-thrown", "CONNECTIVITY_SOURCE", s -> hostile(s, SharedCases.Kind.STATUS)));
        list.add(new Scenario("connectivity.observation-names-the-addressed-device", "CONNECTIVITY_SOURCE", ConformanceKit::connectivityDevice));
        // authentication
        list.add(new Scenario("authenticator.credential-shapes", "DEVICE_AUTHENTICATOR", ConformanceKit::authShapes));
        list.add(new Scenario("authenticator.an-inactive-device-is-forbidden", "DEVICE_AUTHENTICATOR", ConformanceKit::authInactive));
        list.add(new Scenario("authenticator.hostile-credentials-are-refused-never-thrown", "DEVICE_AUTHENTICATOR", ConformanceKit::authHostile));
        list.add(new Scenario("authenticator.results-carry-no-secret", "DEVICE_AUTHENTICATOR", ConformanceKit::authNoSecret));
        // command egress
        list.add(new Scenario("egress.confirmed-states-its-evidence-and-sends-the-stored-bytes", "COMMAND_EGRESS", s -> egressConfirmed(s)));
        list.add(new Scenario("egress.not-sent-only-with-proof-and-a-stable-code", "COMMAND_EGRESS", s -> egressNotSent(s)));
        list.add(new Scenario("egress.doubt-is-uncertain", "COMMAND_EGRESS", s -> egressUncertain(s)));
        list.add(new Scenario("egress.at-most-one-wire-attempt-per-call", "COMMAND_EGRESS", s -> egressOneAttempt(s)));
        list.add(new Scenario("egress.a-wire-exception-is-never-confirmed-and-never-propagates", "COMMAND_EGRESS", s -> egressException(s)));
        list.add(new Scenario("egress.counters-follow-the-outcomes", "COMMAND_EGRESS", s -> egressCounters(s)));
        // the receive path into the core
        list.add(new Scenario("ingest.valid-telemetry-reaches-the-core-exactly-once", "INGEST", ConformanceKit::ingestValidTelemetry));
        list.add(new Scenario("ingest.invalid-telemetry-never-reaches-the-core", "INGEST", ConformanceKit::ingestInvalidTelemetry));
        list.add(new Scenario("ingest.valid-acknowledgement-reaches-the-core-exactly-once", "INGEST", ConformanceKit::ingestValidAck));
        list.add(new Scenario("ingest.invalid-acknowledgement-never-reaches-the-core", "INGEST", ConformanceKit::ingestInvalidAck));
        list.add(new Scenario("ingest.status-reaches-the-core-and-invalid-status-does-not", "INGEST", ConformanceKit::ingestStatus));
        list.add(new Scenario("ingest.an-identical-redelivery-is-stored-once", "INGEST", ConformanceKit::ingestDuplicate));
        return list;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static String verdict(Decoded<?> decoded) {
        return decoded instanceof Decoded.Refused<?> refused ? "REJECT:" + refused.rejection().code() : "ACCEPT";
    }

    private static Decoded<?> decode(Subject s, SharedCases.Kind kind, byte[] payload, UUID device) {
        Subject.Wire wire = s.wire().orElseThrow(() -> new IllegalStateException("subject supplies no wire"));
        AdapterDescriptor a = s.adapter();
        return switch (kind) {
            case TELEMETRY -> ((TelemetryDecoder) a).decodeTelemetry(wire.telemetry(payload, device));
            case ACKNOWLEDGEMENT -> ((AcknowledgementDecoder) a).decodeAcknowledgement(wire.acknowledgement(payload, device));
            case STATUS -> ((ConnectivitySource) a).decodeConnectivity(wire.status(payload, device));
        };
    }

    // ------------------------------------------------------------------------------------------------ descriptor

    private static String descriptorId(Subject s) {
        String id = s.adapter().id();
        if (id == null || !ADAPTER_ID.matcher(id).matches()) return "id is not a stable lower-case identifier";
        return id.equals(s.adapter().id()) ? null : "id is not stable";
    }

    private static String descriptorTransport(Subject s) {
        AdapterDescriptor a = s.adapter();
        if (a.transport() == null) return "no transport";
        if (a.supportedContractMajors() == null || a.supportedContractMajors().isEmpty()) return "no contract major";
        for (Integer major : a.supportedContractMajors()) if (major == null || major < 1) return "a contract major below 1";
        return a.supportedContractMajors().contains(1) ? null : "does not understand contract major 1";
    }

    private static String descriptorCapabilities(Subject s) {
        AdapterDescriptor a = s.adapter();
        Set<AdapterCapability> declared = a.capabilities();
        for (Map.Entry<AdapterCapability, Class<?>> entry : AdapterCapability.contracts().entrySet()) {
            boolean implemented = entry.getValue().isInstance(a);
            boolean claims = declared.contains(entry.getKey());
            if (implemented != claims) return entry.getKey() + (claims ? " declared but not implemented" : " implemented but not declared");
        }
        return null;
    }

    private static String descriptorRegisters(Subject s) {
        AdapterDescriptor a = s.adapter();
        AdapterRegistry registry = new AdapterRegistry(List.of(a));
        if (registry.forTransport(a.transport()).orElse(null) != a) return "the registry does not route the transport to the adapter";
        for (AdapterCapability capability : AdapterCapability.values()) {
            boolean exposed = registry.capability(a.transport(), capability.contract()).isPresent();
            if (exposed != a.capabilities().contains(capability)) return capability + (exposed ? " exposed without being declared" : " declared but not exposed");
        }
        return registry.reports().size() == 1 ? null : "the registry report is not one entry";
    }

    private static String descriptorDiagnostics(Subject s) {
        Map<String, Long> counters = s.adapter().diagnostics();
        if (counters == null) return "no diagnostics";
        for (Map.Entry<String, Long> counter : counters.entrySet()) {
            if (!COUNTER_NAME.matcher(counter.getKey()).matches()) return "a counter name is not a plain identifier";
            String lower = counter.getKey().toLowerCase();
            for (String word : FORBIDDEN_IN_COUNTER_NAMES) {
                if (lower.contains(word) && !lower.equals("acknowledgementsaccepted")) return "a counter name suggests sensitive content";
            }
            if (counter.getValue() == null || counter.getValue() < 0) return "a counter is negative";
        }
        return null;
    }

    private static String lifecycle(Subject s) {
        AdapterLifecycle lifecycle = (AdapterLifecycle) s.adapter();
        lifecycle.start();
        lifecycle.start();
        lifecycle.stop();
        lifecycle.stop();
        lifecycle.start();
        lifecycle.stop();
        lifecycle.start();   // leave it running: a registered adapter is started with the application
        return null;
    }

    // ------------------------------------------------------------------------------------------------ decoders

    private static String sharedCases(Subject s, List<Case> cases) {
        List<String> differences = new ArrayList<>();
        List<String> registered = new ArrayList<>();
        for (Case c : cases) {
            String actual = verdict(decode(s, c.kind(), c.payload(), SharedCases.DEVICE));
            Subject.Registered known = s.registeredDifferences().get(c.id());
            String expected = known != null ? known.verdict() : c.expected();
            if (!actual.equals(expected)) differences.add(c.id() + " expected " + expected + " got " + actual);
            else if (known != null) registered.add(c.id() + " [" + known.registryCase() + "]");
        }
        if (!differences.isEmpty()) return differences.size() + " of " + cases.size() + " shared cases differ: " + String.join("; ", differences);
        return registered.isEmpty() ? null : NOTE + registered.size() + " registered difference(s) held exactly: " + String.join(", ", registered);
    }

    private static List<byte[]> hostilePayloads(SharedCases.Kind kind) {
        List<byte[]> payloads = new ArrayList<>();
        List<Case> seeds = switch (kind) {
            case TELEMETRY -> SharedCases.telemetry();
            case ACKNOWLEDGEMENT -> SharedCases.acknowledgements();
            case STATUS -> SharedCases.statuses();
        };
        Random random = new Random(0x57L);
        for (int i = 0; i < 300; i++) {
            byte[] bytes = seeds.get(random.nextInt(seeds.size())).payload().clone();
            if (bytes.length == 0) continue;
            for (int e = 0; e < 1 + random.nextInt(3); e++) {
                int at = random.nextInt(bytes.length);
                switch (random.nextInt(4)) {
                    case 0 -> bytes[at] = (byte) "{}[]\",:0123456789ntfeE.-+ \t".charAt(random.nextInt(26));
                    case 1 -> bytes = java.util.Arrays.copyOf(bytes, at);
                    case 2 -> bytes[at] = (byte) random.nextInt(256);
                    default -> bytes[at] = 0;
                }
                if (bytes.length == 0) break;
            }
            payloads.add(bytes);
        }
        payloads.add(new byte[0]);
        payloads.add(new byte[]{(byte) 0xff, (byte) 0xfe, 0, 0});
        payloads.add("{\"a\":".repeat(500).getBytes(StandardCharsets.UTF_8));
        payloads.add("[".repeat(5000).getBytes(StandardCharsets.UTF_8));
        payloads.add(new byte[1 << 20]);
        byte[] big = new byte[1 << 20];
        java.util.Arrays.fill(big, (byte) 'a');
        payloads.add(big);
        return payloads;
    }

    private static String hostile(Subject s, SharedCases.Kind kind) {
        long started = System.nanoTime();
        int accepted = 0, refused = 0;
        for (byte[] payload : hostilePayloads(kind)) {
            Decoded<?> decoded = decode(s, kind, payload, SharedCases.DEVICE);
            if (decoded instanceof Decoded.Accepted<?>) accepted++; else refused++;
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        if (refused == 0) return "no hostile input was refused";
        if (millis > 20_000) return "hostile input took " + millis + " ms";
        return null;
    }

    private static String telemetryValues(Subject s) {
        Case legacy = SharedCases.telemetry().get(0);
        Decoded<?> first = decode(s, SharedCases.Kind.TELEMETRY, legacy.payload(), SharedCases.DEVICE);
        Decoded<?> second = decode(s, SharedCases.Kind.TELEMETRY, legacy.payload(), SharedCases.DEVICE);
        if (!(first instanceof Decoded.Accepted<?> accepted) || !(accepted.value() instanceof TelemetryPayload p)) return "the legacy fixture is not accepted";
        if (!p.deviceId().equals(SharedCases.DEVICE)) return "deviceId";
        if (!p.timestamp().equals(Instant.parse("2026-01-01T00:00:00Z"))) return "timestamp";
        if (!"TEMPERATURE_SENSOR".equals(p.sensorType()) || !"CELSIUS".equals(p.unit())) return "sensorType or unit";
        if (!(p.value() instanceof Number number) || number.doubleValue() != 24.5) return "value is not the number 24.5";
        if (p.messageId() != null || p.capabilityKey() != null) return "a legacy sample gained a messageId or capabilityKey";
        if (!(second instanceof Decoded.Accepted<?> again) || !again.value().equals(p)) return "decoding is not deterministic";
        Case versioned = SharedCases.telemetry().stream().filter(c -> c.id().equals("telemetry:versioned-with-message-id")).findFirst().orElseThrow();
        if (!(decode(s, SharedCases.Kind.TELEMETRY, versioned.payload(), SharedCases.DEVICE) instanceof Decoded.Accepted<?> v) || !(v.value() instanceof TelemetryPayload vp)
                || !"00000001-0000-4000-8000-000000000000".equals(vp.messageId())) return "the messageId of a versioned sample is not carried";
        Case bool = SharedCases.telemetry().stream().filter(c -> c.id().equals("telemetry:generic-boolean-fixture")).findFirst().orElseThrow();
        if (!(decode(s, SharedCases.Kind.TELEMETRY, bool.payload(), SharedCases.DEVICE) instanceof Decoded.Accepted<?> b) || !(b.value() instanceof TelemetryPayload bp)
                || !Boolean.TRUE.equals(bp.value()) || !"motion".equals(bp.capabilityKey())) return "a BOOLEAN value or its capabilityKey is not carried";
        return null;
    }

    private static String telemetryIdentity(Subject s) {
        Subject.Wire wire = s.wire().orElseThrow();
        UUID other = UUID.fromString("9b1d3e57-2c44-4a0f-a6d8-71e5c0b3f492");
        var message = wire.telemetryForOtherDevice(SharedCases.telemetry().get(0).payload(), other);
        if (message.isEmpty()) throw new NotApplicable("the device is not part of this transport's framing");
        Decoded<TelemetryPayload> decoded = ((TelemetryDecoder) s.adapter()).decodeTelemetry(message.get());
        return decoded instanceof Decoded.Refused<TelemetryPayload> r && r.rejection().code() == RejectionCode.TOPIC_DEVICE_MISMATCH ? null
                : "a payload for another device than the one addressed was " + verdict(decoded);
    }

    private static String rejectionsCarryNoContent(Subject s) {
        Case malformed = SharedCases.telemetry().stream().filter(c -> c.id().equals("telemetry:legacy-missing-unit")).findFirst().orElseThrow();
        Decoded<?> decoded = decode(s, SharedCases.Kind.TELEMETRY, malformed.payload(), SharedCases.DEVICE);
        if (!(decoded instanceof Decoded.Refused<?> refused)) return "expected a refusal";
        String text = refused.rejection().toString();
        if (!STABLE_CODE.matcher(refused.rejection().code().name()).matches()) return "the code is not a stable token";
        if (text.contains(SharedCases.DEVICE.toString()) || text.contains("TEMPERATURE") || text.contains("deviceId")) return "the rejection text contains payload content";
        return null;
    }

    private static String ackValues(Subject s) {
        Case accepted = SharedCases.acknowledgements().get(1);
        if (!(decode(s, SharedCases.Kind.ACKNOWLEDGEMENT, accepted.payload(), SharedCases.DEVICE) instanceof Decoded.Accepted<?> a) || !(a.value() instanceof AckPayload p))
            return "the STALE rejection fixture is not accepted";
        if (!p.commandId().equals(SharedCases.COMMAND) || p.status() != AckPayload.Status.REJECTED || !"STALE".equals(p.reason())) return "commandId, status or reason";
        if (p.deviceTimestamp().getEpochSecond() != 1767225600L) return "deviceTimestamp";
        Case plain = SharedCases.acknowledgements().get(0);
        if (!(decode(s, SharedCases.Kind.ACKNOWLEDGEMENT, plain.payload(), SharedCases.DEVICE) instanceof Decoded.Accepted<?> b) || !(b.value() instanceof AckPayload q)
                || q.status() != AckPayload.Status.ACCEPTED || q.reason() != null) return "an accepted acknowledgement carries a reason";
        return null;
    }

    private static String connectivityDevice(Subject s) {
        UUID addressed = UUID.fromString("9b1d3e57-2c44-4a0f-a6d8-71e5c0b3f492");
        Decoded<ConnectivityObservation> decoded = ((ConnectivitySource) s.adapter()).decodeConnectivity(s.wire().orElseThrow().status("online".getBytes(StandardCharsets.UTF_8), addressed));
        if (!(decoded instanceof Decoded.Accepted<ConnectivityObservation> accepted)) return "a plain online status was refused";
        if (!accepted.value().deviceId().equals(addressed)) return "the observation names a device other than the addressed one";
        return accepted.value().state() == ConnectivityObservation.State.ONLINE ? null : "online was not ONLINE";
    }

    // ------------------------------------------------------------------------------------------------ authentication

    private static String authShapes(Subject s) {
        Subject.AuthProbe probe = s.auth().orElseThrow(() -> new IllegalStateException("subject supplies no credentials"));
        DeviceAuthenticator authenticator = (DeviceAuthenticator) s.adapter();
        Decoded<AuthenticatedDevice> ok = authenticator.authenticate(probe.message(probe.validCredential()));
        if (!(ok instanceof Decoded.Accepted<AuthenticatedDevice> accepted)) return "a valid credential was refused";
        if (!accepted.value().deviceId().equals(probe.deviceOfValidCredential())) return "the authenticated device is not the credential's device";
        List<RejectionCode> codes = new ArrayList<>();
        for (String bad : new String[]{null, "", probe.wrongSecret(), probe.unknownCredential(), probe.revokedCredential()}) {
            Decoded<AuthenticatedDevice> refused = authenticator.authenticate(probe.message(bad));
            if (!(refused instanceof Decoded.Refused<AuthenticatedDevice> r)) return "a credential that must be refused was accepted";
            codes.add(r.rejection().code());
        }
        if (new java.util.HashSet<>(codes).size() != 1 || codes.get(0) != RejectionCode.UNAUTHENTICATED)
            return "failures are distinguishable or not UNAUTHENTICATED: " + codes;
        return null;
    }

    private static String authInactive(Subject s) {
        Subject.AuthProbe probe = s.auth().orElseThrow();
        String credential = probe.inactiveDeviceCredential();
        if (credential == null) throw new NotApplicable("this subject cannot create an inactive device");
        Decoded<AuthenticatedDevice> decoded = ((DeviceAuthenticator) s.adapter()).authenticate(probe.message(credential));
        return decoded instanceof Decoded.Refused<AuthenticatedDevice> r && r.rejection().code() == RejectionCode.FORBIDDEN_DEVICE ? null : "an inactive device was " + verdict(decoded);
    }

    private static String authHostile(Subject s) {
        Subject.AuthProbe probe = s.auth().orElseThrow();
        DeviceAuthenticator authenticator = (DeviceAuthenticator) s.adapter();
        Random random = new Random(0x58L);
        List<String> hostile = new ArrayList<>(List.of("x".repeat(8192), "\u0000", "' OR '1'='1", "ÿþ", "Bearer", "Bearer \t", "a".repeat(100000)));
        for (int i = 0; i < 200; i++) {
            StringBuilder b = new StringBuilder();
            for (int k = 0, n = random.nextInt(200); k < n; k++) b.append((char) (32 + random.nextInt(95)));
            hostile.add(b.toString());
        }
        for (String credential : hostile) {
            if (authenticator.authenticate(probe.message(credential)) instanceof Decoded.Accepted<?>) return "a hostile credential was accepted";
        }
        return null;
    }

    private static String authNoSecret(Subject s) {
        Subject.AuthProbe probe = s.auth().orElseThrow();
        String credential = probe.validCredential();
        Decoded<AuthenticatedDevice> decoded = ((DeviceAuthenticator) s.adapter()).authenticate(probe.message(credential));
        if (!(decoded instanceof Decoded.Accepted<AuthenticatedDevice> accepted)) return "a valid credential was refused";
        String printed = accepted.value().toString() + decoded;
        String secretPart = credential.length() > 12 ? credential.substring(credential.length() - 12) : credential;
        return printed.contains(secretPart) ? "a result prints part of the credential" : null;
    }

    // ------------------------------------------------------------------------------------------------ egress

    private static final UUID EGRESS_DEVICE = UUID.fromString("5d0c9a30-1b2e-4c3d-9e4f-0a1b2c3d4e5f");

    private static byte[] storedPayload() {
        return ("{\"commandId\":\"" + SharedCases.COMMAND + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true,\"timestamp\":1767225600}").getBytes(StandardCharsets.UTF_8);
    }

    private static EgressProbe.Delivery force(Subject s, EgressProbe.Mode mode) {
        EgressProbe probe = s.egress().orElseThrow(() -> new IllegalStateException("subject supplies no egress probe"));
        return probe.deliver(mode, EGRESS_DEVICE, storedPayload()).orElseThrow(() -> new NotApplicable(probe.cannotForce(mode)));
    }

    private static String egressConfirmed(Subject s) {
        EgressProbe.Delivery d = force(s, EgressProbe.Mode.CONFIRMED);
        if (d.attempt().outcome() != Attempt.Outcome.CONFIRMED) return "expected CONFIRMED, got " + d.attempt().outcome();
        if (d.attempt().evidence() != s.egress().get().expectedEvidence()) return "evidence kind " + d.attempt().evidence() + " is not the transport's " + s.egress().get().expectedEvidence();
        if (d.bytesOnWire() == null || !java.util.Arrays.equals(d.bytesOnWire(), storedPayload())) return "the bytes on the wire are not the stored bytes";
        return null;
    }

    private static String egressNotSent(Subject s) {
        EgressProbe.Delivery d = force(s, EgressProbe.Mode.NOT_SENT);
        if (d.attempt().outcome() != Attempt.Outcome.NOT_SENT) return "expected NOT_SENT, got " + d.attempt().outcome();
        if (d.wireCalls() != 0) return "NOT_SENT although the wire was reached " + d.wireCalls() + " time(s)";
        return d.attempt().code() != null && STABLE_CODE.matcher(d.attempt().code()).matches() ? null : "no stable code";
    }

    private static String egressUncertain(Subject s) {
        EgressProbe.Delivery d = force(s, EgressProbe.Mode.UNCERTAIN);
        return d.attempt().outcome() == Attempt.Outcome.UNCERTAIN ? null : "doubt was classified " + d.attempt().outcome();
    }

    private static String egressOneAttempt(Subject s) {
        for (EgressProbe.Mode mode : new EgressProbe.Mode[]{EgressProbe.Mode.CONFIRMED, EgressProbe.Mode.NOT_SENT, EgressProbe.Mode.UNCERTAIN}) {
            EgressProbe probe = s.egress().orElseThrow();
            var delivery = probe.deliver(mode, EGRESS_DEVICE, storedPayload());
            if (delivery.isPresent() && delivery.get().wireCalls() > 1) return mode + " reached the wire " + delivery.get().wireCalls() + " times";
        }
        return null;
    }

    private static String egressException(Subject s) {
        EgressProbe probe = s.egress().orElseThrow();
        var delivery = probe.deliver(EgressProbe.Mode.WIRE_FAILS_WITH_EXCEPTION, EGRESS_DEVICE, storedPayload());
        if (delivery.isEmpty()) throw new NotApplicable(probe.cannotForce(EgressProbe.Mode.WIRE_FAILS_WITH_EXCEPTION));
        if (delivery.get().thrown() != null) return "the exception propagated out of deliver(): " + delivery.get().thrown().getClass().getSimpleName();
        return delivery.get().attempt().outcome() == Attempt.Outcome.CONFIRMED ? "a failing wire was reported CONFIRMED" : null;
    }

    private static String egressCounters(Subject s) {
        EgressProbe probe = s.egress().orElseThrow();
        long before = s.adapter().diagnostics().values().stream().mapToLong(Long::longValue).sum();
        int delivered = 0;
        for (EgressProbe.Mode mode : new EgressProbe.Mode[]{EgressProbe.Mode.CONFIRMED, EgressProbe.Mode.NOT_SENT, EgressProbe.Mode.UNCERTAIN}) {
            if (probe.deliver(mode, EGRESS_DEVICE, storedPayload()).isPresent()) delivered++;
        }
        long after = s.adapter().diagnostics().values().stream().mapToLong(Long::longValue).sum();
        return after - before >= delivered ? null : "diagnostics grew by " + (after - before) + " after " + delivered + " deliveries";
    }

    // ------------------------------------------------------------------------------------------------ ingest

    private static IngestProbe ingest(Subject s, IngestProbe.Kind kind) {
        IngestProbe probe = s.ingest().orElseThrow(() -> new NotApplicable("this subject has no receive path to observe"));
        if (!probe.supports(kind)) throw new NotApplicable("this transport has no " + kind + " input");
        return probe;
    }

    private static String ingestValidTelemetry(Subject s) {
        IngestProbe probe = ingest(s, IngestProbe.Kind.TELEMETRY);
        UUID device = probe.newDevice();
        for (Case c : SharedCases.telemetry()) {
            if (!c.accepts()) continue;
            probe.reset();
            probe.send(IngestProbe.Kind.TELEMETRY, probe.forDevice(c.payload(), device), device);
            if (probe.coreCalls(IngestProbe.Kind.TELEMETRY) != 1) return c.id() + " reached the core " + probe.coreCalls(IngestProbe.Kind.TELEMETRY) + " times";
        }
        return null;
    }

    private static String ingestInvalidTelemetry(Subject s) {
        IngestProbe probe = ingest(s, IngestProbe.Kind.TELEMETRY);
        UUID device = probe.newDevice();
        for (Case c : SharedCases.telemetry()) {
            if (c.accepts() || registeredAsAccepted(s, c)) continue;
            probe.reset();
            probe.send(IngestProbe.Kind.TELEMETRY, probe.forDevice(c.payload(), device), device);
            if (probe.coreCalls(IngestProbe.Kind.TELEMETRY) != 0) return c.id() + " reached the core";
            if (probe.storedTelemetry(device) > 0) return c.id() + " left a stored sample";
        }
        // a payload for another device than the one the transport authenticated or addressed never reaches the core either
        UUID other = probe.newDevice();
        probe.reset();
        probe.send(IngestProbe.Kind.TELEMETRY, probe.forDevice(SharedCases.telemetry().get(0).payload(), other), device);
        return probe.coreCalls(IngestProbe.Kind.TELEMETRY) == 0 ? null : "a sample for another device reached the core";
    }

    /** A case this transport legitimately accepts (a registered class E difference): it is not an invalid input FOR THIS TRANSPORT, and the shared-case scenario already holds the registered verdict exactly. */
    private static boolean registeredAsAccepted(Subject s, Case c) {
        Subject.Registered registered = s.registeredDifferences().get(c.id());
        return registered != null && registered.verdict().equals("ACCEPT");
    }

    private static String ingestValidAck(Subject s) {
        IngestProbe probe = ingest(s, IngestProbe.Kind.ACKNOWLEDGEMENT);
        UUID device = probe.newDevice();
        for (Case c : SharedCases.acknowledgements()) {
            if (!c.accepts()) continue;
            probe.reset();
            probe.send(IngestProbe.Kind.ACKNOWLEDGEMENT, c.payload(), device);
            if (probe.coreCalls(IngestProbe.Kind.ACKNOWLEDGEMENT) != 1) return c.id() + " reached the core " + probe.coreCalls(IngestProbe.Kind.ACKNOWLEDGEMENT) + " times";
        }
        return null;
    }

    private static String ingestInvalidAck(Subject s) {
        IngestProbe probe = ingest(s, IngestProbe.Kind.ACKNOWLEDGEMENT);
        UUID device = probe.newDevice();
        for (Case c : SharedCases.acknowledgements()) {
            if (c.accepts() || registeredAsAccepted(s, c)) continue;
            probe.reset();
            probe.send(IngestProbe.Kind.ACKNOWLEDGEMENT, c.payload(), device);
            if (probe.coreCalls(IngestProbe.Kind.ACKNOWLEDGEMENT) != 0) return c.id() + " reached the core";
        }
        return null;
    }

    private static String ingestStatus(Subject s) {
        IngestProbe probe = ingest(s, IngestProbe.Kind.STATUS);
        UUID device = probe.newDevice();
        for (Case c : SharedCases.statuses()) {
            probe.reset();
            probe.send(IngestProbe.Kind.STATUS, c.payload(), device);
            int calls = probe.coreCalls(IngestProbe.Kind.STATUS);
            if (c.accepts() && calls != 1) return c.id() + " reached the core " + calls + " times";
            if (!c.accepts() && calls != 0) return c.id() + " reached the core";
        }
        return null;
    }

    private static String ingestDuplicate(Subject s) {
        IngestProbe probe = ingest(s, IngestProbe.Kind.TELEMETRY);
        UUID device = probe.newDevice();
        if (probe.storedTelemetry(device) < 0) throw new NotApplicable("no database at this level; asserted by the LIVE run");
        byte[] versioned = SharedCases.telemetry().stream().filter(c -> c.id().equals("telemetry:versioned-with-message-id")).findFirst().orElseThrow().payload();
        byte[] payload = probe.forDevice(versioned, device);
        probe.send(IngestProbe.Kind.TELEMETRY, payload, device);
        probe.send(IngestProbe.Kind.TELEMETRY, payload, device);
        probe.send(IngestProbe.Kind.TELEMETRY, payload, device);
        long stored = probe.storedTelemetry(device);
        return stored == 1 ? null : "three identical deliveries stored " + stored + " samples";
    }
}
