package com.nexus.adapter.conformance;

import com.nexus.adapter.http.HttpJsonDecoder;
import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.AdapterDescriptor;
import com.nexus.adapter.spi.AdapterLifecycle;
import com.nexus.adapter.spi.Decoded;
import com.nexus.adapter.spi.IntakeResult;
import com.nexus.adapter.spi.RawMessage;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryDecoder;
import com.nexus.adapter.spi.TelemetryIntake;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.adapter.spi.TransportContext;
import com.nexus.adapter.spi.TransportKind;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A deliberately minimal FOURTH adapter, a serial-line transport, that exists only in test code to prove that a new transport needs nothing from
 * the Digital Twin, the Rule Engine or the Phase 4 reliability policies (ledger P5-108).
 *
 * <p>What it is: newline-framed JSON samples on a port that the server binds to one device. It implements the lifecycle and the telemetry
 * decoder, and it feeds the core ONLY through the {@link TelemetryIntake} port. It declares nothing else, so the conformance kit reports every
 * other capability NOT_SUPPORTED for it (it has no command path: it is ingest-only).
 *
 * <p>What it is not: it is not a production adapter. It is not a Spring bean, it lives outside {@code src/main}, no main source references it,
 * and the transport it stands in for is not a {@link TransportKind} value, so no device can be assigned to it. Because the enum has no serial
 * value (adding one is the only SPI-level change a real new transport needs, together with the widening of the V41 CHECK constraint), the test
 * registry places it in the SIMULATOR slot, which is free in that registry.
 *
 * <p>It reuses the strict JSON contract decoder instead of re-implementing the contract rules: one rule set, many framings.
 */
public final class SerialLikeAdapter implements AdapterDescriptor, AdapterLifecycle, TelemetryDecoder {
    public static final String ID = "serial-test";
    /** A frame longer than this is dropped whole: a serial line has no length field, so the receiver must bound it. */
    public static final int MAX_FRAME_BYTES = 1024;

    private final HttpJsonDecoder decoder = new HttpJsonDecoder();
    private final TelemetryIntake intake;
    private final Clock clock;
    private final Map<String, UUID> ports = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong framesAccepted = new AtomicLong();
    private final AtomicLong framesRejected = new AtomicLong();

    public SerialLikeAdapter(TelemetryIntake intake, Clock clock) {
        this.intake = intake;
        this.clock = clock;
    }

    @Override public String id() { return ID; }

    /** The stand-in slot (see the class comment). */
    @Override public TransportKind transport() { return TransportKind.SIMULATOR; }

    @Override public Set<Integer> supportedContractMajors() { return Set.of(1); }

    @Override
    public Set<AdapterCapability> capabilities() {
        return EnumSet.of(AdapterCapability.LIFECYCLE, AdapterCapability.TELEMETRY_DECODER);
    }

    @Override
    public Map<String, Long> diagnostics() {
        Map<String, Long> counters = new TreeMap<>();
        counters.put("framesAccepted", framesAccepted.get());
        counters.put("framesRejected", framesRejected.get());
        counters.put("ports", (long) ports.size());
        return counters;
    }

    @Override public void start() { running.set(true); }
    @Override public void stop() { running.set(false); }

    /** The server's binding of a physical port to a device; identity never comes from the frame. */
    public void bind(String port, UUID device) {
        ports.put(port, device);
    }

    @Override
    public Decoded<TelemetryPayload> decodeTelemetry(RawMessage message) {
        byte[] frame = message.payload();
        Decoded<TelemetryPayload> result;
        if (frame.length == 0 || frame.length > MAX_FRAME_BYTES || frame[frame.length - 1] != '\n') {
            result = Decoded.refused(frame.length > MAX_FRAME_BYTES ? RejectionCode.PAYLOAD_TOO_LARGE : RejectionCode.MALFORMED_JSON);
        } else {
            int end = frame.length - 1;
            if (end > 0 && frame[end - 1] == '\r') end--;
            for (int i = 0; i < end; i++) {
                if (frame[i] == '\n') return count(Decoded.refused(RejectionCode.MALFORMED_JSON));   // exactly one line per frame
            }
            result = decoder.decodeTelemetry(java.util.Arrays.copyOf(frame, end), message.metadata().get("x-nexus-contract-version"));
        }
        return count(result);
    }

    private Decoded<TelemetryPayload> count(Decoded<TelemetryPayload> result) {
        (result.isAccepted() ? framesAccepted : framesRejected).incrementAndGet();
        return result;
    }

    /** One frame arrived on a port: decode it, check it is for the device bound to the port, and hand it to the core's telemetry port. */
    public IntakeResult onFrame(String port, byte[] frame) {
        UUID bound = ports.get(port);
        if (!running.get() || bound == null) return IntakeResult.refused(RejectionCode.FORBIDDEN_DEVICE);
        Decoded<TelemetryPayload> decoded = decodeTelemetry(new RawMessage(frame, null));
        if (decoded instanceof Decoded.Refused<TelemetryPayload> refused) return new IntakeResult.Refused(refused.rejection());
        TelemetryPayload payload = ((Decoded.Accepted<TelemetryPayload>) decoded).value();
        if (!payload.deviceId().equals(bound)) return IntakeResult.refused(RejectionCode.DEVICE_MISMATCH);
        return intake.submit(payload, new TransportContext(ID, transport(), bound, clock.instant(), "serial"));
    }
}
