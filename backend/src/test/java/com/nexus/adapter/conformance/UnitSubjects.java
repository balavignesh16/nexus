package com.nexus.adapter.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.adapter.http.CredentialStore;
import com.nexus.adapter.http.HttpWebhookAdapter;
import com.nexus.adapter.http.KeyFormat;
import com.nexus.adapter.http.KeyHasher;
import com.nexus.adapter.mqtt.MqttJsonAdapter;
import com.nexus.adapter.mqtt.MqttPublisher;
import com.nexus.adapter.simulator.SimulatedDevice;
import com.nexus.adapter.simulator.SimulatorAdapter;
import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.AcknowledgementIntake;
import com.nexus.adapter.spi.AdapterDescriptor;
import com.nexus.adapter.spi.Attempt;
import com.nexus.adapter.spi.CommandEnvelope;
import com.nexus.adapter.spi.EvidenceKind;
import com.nexus.adapter.spi.IntakeResult;
import com.nexus.adapter.spi.RawMessage;
import com.nexus.adapter.spi.TelemetryIntake;
import com.nexus.adapter.spi.TelemetryPayload;
import jakarta.validation.Validation;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** The subjects of the UNIT level: real adapter classes over controllable collaborators, no database and no broker. */
public final class UnitSubjects {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();

    private UnitSubjects() {}

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------------ MQTT

    /** The registered class E differences of the MQTT path: it ignores contractVersion and requires (contracts/parity/known-differences.json). */
    static final Map<String, Subject.Registered> MQTT_REGISTERED = Map.of(
            "telemetry:unsupported-major", new Subject.Registered("ACCEPT", "telemetry:contractVersion-2.0-field"),
            "telemetry:invalid-version-text", new Subject.Registered("ACCEPT", "telemetry:contractVersion-invalid-text"),
            "telemetry:required-feature-not-understood", new Subject.Registered("ACCEPT", "telemetry:requires-non-empty"),
            "ack:unsupported-major", new Subject.Registered("ACCEPT", "ack:contractVersion-2.0-field"));

    /** A broker client whose outcome the test chooses; counts and records what reached it. */
    public static final class FakeBroker implements MqttPublisher {
        volatile Kind kind = Kind.CONFIRMED;
        volatile String code = "OK";
        volatile boolean throwing;
        final AtomicInteger calls = new AtomicInteger();
        volatile byte[] lastPayload;

        @Override
        public Result publish(String topic, byte[] payload, Duration timeout) {
            calls.incrementAndGet();
            lastPayload = payload.clone();
            if (throwing) throw new IllegalStateException("broker client failed");
            return new Result(kind, code);
        }

        @Override public boolean connected() { return true; }
    }

    public static Subject mqtt() {
        FakeBroker broker = new FakeBroker();
        MqttJsonAdapter adapter = new MqttJsonAdapter(MAPPER, Validation.buildDefaultValidatorFactory().getValidator(), broker);
        Subject.Wire wire = new Subject.Wire() {
            private RawMessage on(String suffix, byte[] payload, UUID device) {
                return new RawMessage(payload, "nexus/devices/" + device + suffix);
            }

            @Override public RawMessage telemetry(byte[] payload, UUID device) { return on("/telemetry", payload, device); }
            @Override public RawMessage acknowledgement(byte[] payload, UUID device) { return on("/commands/ack", payload, device); }
            @Override public RawMessage status(byte[] payload, UUID device) { return on("/status", payload, device); }

            @Override
            public Optional<RawMessage> telemetryForOtherDevice(byte[] payload, UUID addressed) {
                return Optional.of(on("/telemetry", payload, addressed));
            }
        };
        Subject.EgressProbe egress = new Subject.EgressProbe() {
            @Override
            public Optional<Delivery> deliver(Mode mode, UUID device, byte[] payload) {
                broker.calls.set(0);
                broker.throwing = mode == Mode.WIRE_FAILS_WITH_EXCEPTION;
                broker.kind = switch (mode) {
                    case CONFIRMED -> MqttPublisher.Kind.CONFIRMED;
                    case NOT_SENT -> MqttPublisher.Kind.NOT_SENT;
                    default -> MqttPublisher.Kind.UNCERTAIN;
                };
                broker.code = mode == Mode.NOT_SENT ? "BROKER_DISCONNECTED" : mode == Mode.UNCERTAIN ? "PUBLISH_TIMEOUT" : "OK";
                // the fake reports NOT_SENT without having been reached, like the real bounded publisher refusing before the client is touched
                Throwable thrown = null;
                Attempt attempt = null;
                try {
                    attempt = adapter.deliver(new CommandEnvelope(UUID.randomUUID(), device, payload, "hash", 1767225600L, "c"), Duration.ofSeconds(1));
                } catch (Throwable t) {
                    thrown = t;
                }
                int reached = mode == Mode.NOT_SENT ? 0 : broker.calls.get();
                return Optional.of(new Delivery(attempt, reached, broker.lastPayload, thrown));
            }

            @Override public EvidenceKind expectedEvidence() { return EvidenceKind.BROKER_CONFIRMED; }
        };
        return new SubjectImpl("mqtt-json", Subject.Level.UNIT, adapter, Optional.of(wire), Optional.empty(), Optional.of(egress), Optional.empty(), Map.of(),
                MQTT_REGISTERED);
    }

    // ------------------------------------------------------------------------------------------------ HTTP

    private static final byte[] PEPPER = "conformance-pepper-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    public static Subject http() {
        KeyHasher hasher = new KeyHasher(PEPPER);
        SecureRandom random = new SecureRandom();
        Map<String, CredentialStore.Stored> credentials = new HashMap<>();
        CredentialStore store = new CredentialStore() {
            @Override public Optional<Stored> find(String lookupId) { return Optional.ofNullable(credentials.get(lookupId)); }
            @Override public void recordContact(UUID credentialId, UUID deviceId) { }
        };
        HttpWebhookAdapter adapter = new HttpWebhookAdapter(store, hasher);
        UUID device = UUID.fromString("8c1d9e57-2c44-4a0f-a6d8-71e5c0b3f492");
        UUID inactiveDevice = UUID.fromString("7c1d9e57-2c44-4a0f-a6d8-71e5c0b3f492");

        class Issued {
            final String key;

            Issued(UUID deviceId, String status, String deviceStatus) {
                String lookup = KeyFormat.newLookupId(random), secret = KeyFormat.newSecret(random);
                key = KeyFormat.format(lookup, secret);
                credentials.put(lookup, new CredentialStore.Stored(UUID.randomUUID(), deviceId, lookup, hasher.hash(secret), KeyHasher.ALGORITHM, status, null,
                        Instant.parse("2026-01-01T00:00:00Z"), deviceStatus, "HTTP_WEBHOOK"));
            }
        }
        String valid = new Issued(device, "ACTIVE", "ACTIVE").key;
        String revoked = new Issued(device, "REVOKED", "ACTIVE").key;
        String inactive = new Issued(inactiveDevice, "ACTIVE", "INACTIVE").key;
        KeyFormat.Parsed parsed = KeyFormat.parse(valid).orElseThrow();
        String wrongSecret = KeyFormat.format(parsed.lookupId(), "A".repeat(KeyFormat.SECRET_LENGTH));
        String unknown = KeyFormat.format("0".repeat(KeyFormat.LOOKUP_LENGTH), parsed.secret());

        Subject.Wire wire = new Subject.Wire() {
            @Override public RawMessage telemetry(byte[] payload, UUID d) { return new RawMessage(payload, null); }
            @Override public RawMessage acknowledgement(byte[] payload, UUID d) { return new RawMessage(payload, null); }
            @Override public RawMessage status(byte[] payload, UUID d) { return new RawMessage(payload, null); }
        };
        Subject.AuthProbe auth = new Subject.AuthProbe() {
            @Override public String validCredential() { return valid; }
            @Override public UUID deviceOfValidCredential() { return device; }
            @Override public String wrongSecret() { return wrongSecret; }
            @Override public String unknownCredential() { return unknown; }
            @Override public String revokedCredential() { return revoked; }
            @Override public String inactiveDeviceCredential() { return inactive; }

            @Override
            public RawMessage message(String credential) {
                Map<String, String> headers = new HashMap<>();
                if (credential != null) headers.put(HttpWebhookAdapter.AUTHORIZATION, "Bearer " + credential);
                return new RawMessage(new byte[0], null, headers);
            }
        };
        return new SubjectImpl("http-webhook", Subject.Level.UNIT, adapter, Optional.of(wire), Optional.empty(), Optional.empty(), Optional.of(auth), Map.of());
    }

    // ------------------------------------------------------------------------------------------------ simulator

    /** A device that records what it was handed and answers as scripted. */
    static final class ScriptedDevice implements SimulatedDevice {
        final UUID id;
        volatile boolean running = true;
        volatile boolean failing;
        final AtomicInteger calls = new AtomicInteger();
        volatile byte[] lastPayload;

        ScriptedDevice(UUID id) { this.id = id; }

        @Override public UUID deviceId() { return id; }
        @Override public boolean running() { return running; }

        @Override
        public Optional<AckPayload> onCommand(CommandEnvelope command) {
            calls.incrementAndGet();
            lastPayload = command.payload();
            if (failing) throw new IllegalStateException("device failed");
            return Optional.of(new AckPayload(command.commandId(), AckPayload.Status.ACCEPTED, CLOCK.instant(), null));
        }
    }

    /** Forces the three delivery outcomes of the in-process simulator transport with a scripted device. */
    public static Subject.EgressProbe simulatorEgress(SimulatorAdapter adapter) {
        return new Subject.EgressProbe() {
            @Override
            public Optional<Delivery> deliver(Mode mode, UUID device, byte[] payload) {
                adapter.start();
                adapter.unregister(device);
                ScriptedDevice scripted = new ScriptedDevice(device);
                if (mode != Mode.NOT_SENT) adapter.register(scripted);
                scripted.failing = mode == Mode.UNCERTAIN || mode == Mode.WIRE_FAILS_WITH_EXCEPTION;
                Throwable thrown = null;
                Attempt attempt = null;
                try {
                    attempt = adapter.deliver(new CommandEnvelope(UUID.randomUUID(), device, payload, "hash", 1767225600L, "c"), Duration.ofSeconds(1));
                } catch (Throwable t) {
                    thrown = t;
                }
                return Optional.of(new Delivery(attempt, scripted.calls.get(), scripted.lastPayload, thrown));
            }

            @Override public EvidenceKind expectedEvidence() { return EvidenceKind.SIMULATOR_DELIVERED; }
        };
    }

    public static Subject simulator() {
        TelemetryIntake telemetry = (payload, context) -> IntakeResult.accepted();
        AcknowledgementIntake acknowledgements = (payload, context) -> IntakeResult.accepted();
        SimulatorAdapter adapter = new SimulatorAdapter(telemetry, acknowledgements, CLOCK);
        adapter.start();
        Subject.EgressProbe egress = simulatorEgress(adapter);
        return new SubjectImpl("simulator", Subject.Level.UNIT, adapter, Optional.empty(), Optional.empty(), Optional.of(egress), Optional.empty(), Map.of());
    }

    // ------------------------------------------------------------------------------------------------ the fourth, test-only adapter

    /** Records what the core's telemetry port was handed. */
    public static final class RecordingCore implements TelemetryIntake {
        final List<TelemetryPayload> telemetry = new ArrayList<>();

        @Override
        public IntakeResult submit(TelemetryPayload payload, com.nexus.adapter.spi.TransportContext context) {
            telemetry.add(payload);
            return IntakeResult.accepted();
        }
    }

    public static Subject serial() {
        RecordingCore core = new RecordingCore();
        SerialLikeAdapter adapter = new SerialLikeAdapter(core, CLOCK);
        adapter.start();
        Subject.Wire wire = new Subject.Wire() {
            private RawMessage frame(byte[] payload) {
                byte[] framed = java.util.Arrays.copyOf(payload, payload.length + 1);
                framed[payload.length] = '\n';
                return new RawMessage(framed, null);
            }

            @Override public RawMessage telemetry(byte[] payload, UUID d) { return frame(payload); }
            @Override public RawMessage acknowledgement(byte[] payload, UUID d) { return frame(payload); }
            @Override public RawMessage status(byte[] payload, UUID d) { return frame(payload); }
        };
        Subject.IngestProbe ingest = new Subject.IngestProbe() {
            private int port;

            @Override public boolean supports(Kind kind) { return kind == Kind.TELEMETRY; }

            @Override
            public UUID newDevice() {
                UUID id = UUID.randomUUID();
                adapter.bind("port-" + id, id);
                return id;
            }

            @Override
            public void send(Kind kind, byte[] payload, UUID device) {
                byte[] framed = java.util.Arrays.copyOf(payload, payload.length + 1);
                framed[payload.length] = '\n';
                adapter.onFrame("port-" + device, framed);
            }

            @Override public int coreCalls(Kind kind) { return core.telemetry.size(); }
            @Override public void reset() { core.telemetry.clear(); }
        };
        return new SubjectImpl("serial-test", Subject.Level.UNIT, adapter, Optional.of(wire), Optional.of(ingest), Optional.empty(), Optional.empty(), Map.of());
    }

    // ------------------------------------------------------------------------------------------------ plumbing

    record SubjectImpl(String name, Level level, AdapterDescriptor adapter, Optional<Wire> wire, Optional<IngestProbe> ingest, Optional<EgressProbe> egress,
                       Optional<AuthProbe> auth, Map<String, String> notApplicable, Map<String, Registered> registeredDifferences) implements Subject {
        SubjectImpl(String name, Level level, AdapterDescriptor adapter, Optional<Wire> wire, Optional<IngestProbe> ingest, Optional<EgressProbe> egress,
                    Optional<AuthProbe> auth, Map<String, String> notApplicable) {
            this(name, level, adapter, wire, ingest, egress, auth, notApplicable, Map.of());
        }
    }
}
