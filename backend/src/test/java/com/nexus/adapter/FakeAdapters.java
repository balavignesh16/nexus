package com.nexus.adapter;

import com.nexus.adapter.spi.AcknowledgementDecoder;
import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.AdapterDescriptor;
import com.nexus.adapter.spi.AdapterLifecycle;
import com.nexus.adapter.spi.Attempt;
import com.nexus.adapter.spi.CommandEgress;
import com.nexus.adapter.spi.CommandEnvelope;
import com.nexus.adapter.spi.Decoded;
import com.nexus.adapter.spi.RawMessage;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryDecoder;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.adapter.spi.TransportKind;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Minimal in-test adapters used by the P5.2 tests (never production code). */
final class FakeAdapters {
    private FakeAdapters() {}

    /** An ingest-only adapter: a telemetry decoder and nothing else. */
    static class IngestOnly implements AdapterDescriptor, TelemetryDecoder {
        final String id;
        final TransportKind transport;
        final AtomicLong decoded = new AtomicLong();

        IngestOnly(String id, TransportKind transport) {
            this.id = id;
            this.transport = transport;
        }

        @Override public String id() { return id; }
        @Override public TransportKind transport() { return transport; }
        @Override public Set<Integer> supportedContractMajors() { return Set.of(1); }
        @Override public Set<AdapterCapability> capabilities() { return EnumSet.of(AdapterCapability.TELEMETRY_DECODER); }
        @Override public Map<String, Long> diagnostics() { return Map.of("decoded", decoded.get()); }

        @Override
        public Decoded<TelemetryPayload> decodeTelemetry(RawMessage message) {
            decoded.incrementAndGet();
            return Decoded.refused(RejectionCode.MALFORMED_JSON);
        }
    }

    /** An adapter with a lifecycle, an acknowledgement decoder and command egress whose outcome is chosen by the test. */
    static class FullAdapter implements AdapterDescriptor, AdapterLifecycle, AcknowledgementDecoder, CommandEgress {
        final String id;
        final TransportKind transport;
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger deliveries = new AtomicInteger();
        volatile Function<CommandEnvelope, Attempt> behaviour = c -> Attempt.confirmed(com.nexus.adapter.spi.EvidenceKind.SIMULATOR_DELIVERED);

        FullAdapter(String id, TransportKind transport) {
            this.id = id;
            this.transport = transport;
        }

        @Override public String id() { return id; }
        @Override public TransportKind transport() { return transport; }
        @Override public Set<Integer> supportedContractMajors() { return Set.of(1); }
        @Override public Set<AdapterCapability> capabilities() {
            return EnumSet.of(AdapterCapability.LIFECYCLE, AdapterCapability.ACKNOWLEDGEMENT_DECODER, AdapterCapability.COMMAND_EGRESS);
        }
        @Override public Map<String, Long> diagnostics() { return Map.of("deliveries", (long) deliveries.get()); }
        @Override public void start() { starts.incrementAndGet(); }
        @Override public void stop() { stops.incrementAndGet(); }
        @Override public Decoded<AckPayload> decodeAcknowledgement(RawMessage message) { return Decoded.refused(RejectionCode.INVALID_FIELDS); }

        @Override
        public Attempt deliver(CommandEnvelope command, Duration timeout) {
            deliveries.incrementAndGet();
            return behaviour.apply(command);
        }
    }

    /** Declares COMMAND_EGRESS without implementing it. */
    static class LiarAdapter implements AdapterDescriptor {
        @Override public String id() { return "liar"; }
        @Override public TransportKind transport() { return TransportKind.HTTP_WEBHOOK; }
        @Override public Set<Integer> supportedContractMajors() { return Set.of(1); }
        @Override public Set<AdapterCapability> capabilities() { return EnumSet.of(AdapterCapability.COMMAND_EGRESS); }
        @Override public Map<String, Long> diagnostics() { return Map.of(); }
    }

    /** Implements COMMAND_EGRESS without declaring it. */
    static class ShyAdapter implements AdapterDescriptor, CommandEgress {
        @Override public String id() { return "shy"; }
        @Override public TransportKind transport() { return TransportKind.SIMULATOR; }
        @Override public Set<Integer> supportedContractMajors() { return Set.of(1); }
        @Override public Set<AdapterCapability> capabilities() { return EnumSet.noneOf(AdapterCapability.class); }
        @Override public Map<String, Long> diagnostics() { return Map.of(); }
        @Override public Attempt deliver(CommandEnvelope command, Duration timeout) { return Attempt.notSent("NEVER"); }
    }
}
