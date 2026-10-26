package com.nexus.adapter.simulator;

import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.AcknowledgementIntake;
import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.AdapterDescriptor;
import com.nexus.adapter.spi.AdapterLifecycle;
import com.nexus.adapter.spi.Attempt;
import com.nexus.adapter.spi.CommandEgress;
import com.nexus.adapter.spi.CommandEnvelope;
import com.nexus.adapter.spi.EvidenceKind;
import com.nexus.adapter.spi.IntakeResult;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryIntake;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.adapter.spi.TransportContext;
import com.nexus.adapter.spi.TransportKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The in-process simulator transport: devices that live inside the backend process talk to the core through the same intake ports as any other
 * adapter and receive commands through the same egress capability, with no broker and no network. It exists for tests, demonstrations and the
 * conformance suite, and it is the proof that a new transport needs no change to the Twin or the Rule engines.
 *
 * <p>Only present with {@code nexus.adapter.simulator.enabled=true}. It is NOT the simulator application (the Python process that publishes over
 * MQTT, whose devices keep the MQTT transport), and a device's SIMULATED/REAL mode is independent of its SIMULATOR transport.
 *
 * <p>A delivered command is CONFIRMED with {@link EvidenceKind#SIMULATOR_DELIVERED}: the in-process device accepted the call, which says nothing
 * about any physical device. The device's acknowledgement goes back through the acknowledgement intake port like any other.
 */
@Component
@ConditionalOnProperty(name = "nexus.adapter.simulator.enabled", havingValue = "true")
public class SimulatorAdapter implements AdapterDescriptor, AdapterLifecycle, CommandEgress {
    public static final String ID = "simulator";

    private final TelemetryIntake telemetryIntake;
    private final AcknowledgementIntake acknowledgementIntake;
    private final Clock clock;
    private final Map<UUID, SimulatedDevice> devices = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong telemetry = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong refusedDeliveries = new AtomicLong();
    private final AtomicLong acknowledgements = new AtomicLong();

    @Autowired
    public SimulatorAdapter(TelemetryIntake telemetryIntake, AcknowledgementIntake acknowledgementIntake) {
        this(telemetryIntake, acknowledgementIntake, Clock.systemUTC());
    }

    public SimulatorAdapter(TelemetryIntake telemetryIntake, AcknowledgementIntake acknowledgementIntake, Clock clock) {
        this.telemetryIntake = telemetryIntake;
        this.acknowledgementIntake = acknowledgementIntake;
        this.clock = clock;
    }

    @Override public String id() { return ID; }
    @Override public TransportKind transport() { return TransportKind.SIMULATOR; }
    @Override public Set<Integer> supportedContractMajors() { return Set.of(1); }

    @Override
    public Set<AdapterCapability> capabilities() {
        return EnumSet.of(AdapterCapability.LIFECYCLE, AdapterCapability.COMMAND_EGRESS);
    }

    @Override
    public Map<String, Long> diagnostics() {
        Map<String, Long> counters = new TreeMap<>();
        counters.put("devices", (long) devices.size());
        counters.put("telemetry", telemetry.get());
        counters.put("commandsDelivered", delivered.get());
        counters.put("commandsRefused", refusedDeliveries.get());
        counters.put("acknowledgements", acknowledgements.get());
        return counters;
    }

    @Override public void start() { running.set(true); }
    @Override public void stop() { running.set(false); }
    public boolean running() { return running.get(); }

    public void register(SimulatedDevice device) {
        devices.put(device.deviceId(), device);
    }

    public void unregister(UUID deviceId) {
        devices.remove(deviceId);
    }

    /** Whether an in-process device is registered and running for the id (the rule the transport's eligibility would use once delivery is routed). */
    public boolean deviceRunning(UUID deviceId) {
        SimulatedDevice device = devices.get(deviceId);
        return running.get() && device != null && device.running();
    }

    // ---- ingest: typed objects, no decoding

    /** A running device publishes a sample. The core decides; the server-side assignment is checked by the intake port. */
    public IntakeResult publishTelemetry(UUID deviceId, TelemetryPayload payload) {
        if (!running.get()) return IntakeResult.refused(RejectionCode.UNAUTHENTICATED);
        SimulatedDevice device = devices.get(deviceId);
        if (device == null || !device.running()) return IntakeResult.refused(RejectionCode.FORBIDDEN_DEVICE);
        telemetry.incrementAndGet();
        return telemetryIntake.submit(payload, context(deviceId));
    }

    public IntakeResult publishAcknowledgement(UUID deviceId, AckPayload payload) {
        if (!running.get()) return IntakeResult.refused(RejectionCode.UNAUTHENTICATED);
        acknowledgements.incrementAndGet();
        return acknowledgementIntake.submit(payload, context(deviceId));
    }

    private TransportContext context(UUID deviceId) {
        return new TransportContext(ID, TransportKind.SIMULATOR, deviceId, clock.instant(), "simulator");
    }

    // ---- command egress

    /**
     * Hands the stored bytes to the in-process device. NOT_SENT only with proof that the device was never called (adapter stopped, no such
     * device, device not running); once the device has been called any failure is UNCERTAIN. The acknowledgement the device returns is
     * submitted through the acknowledgement port before this method returns.
     */
    @Override
    public Attempt deliver(CommandEnvelope command, Duration timeout) {
        if (!running.get()) {
            refusedDeliveries.incrementAndGet();
            return Attempt.notSent("ADAPTER_STOPPED");
        }
        SimulatedDevice device = devices.get(command.deviceId());
        if (device == null) {
            refusedDeliveries.incrementAndGet();
            return Attempt.notSent("DEVICE_NOT_REGISTERED");
        }
        if (!device.running()) {
            refusedDeliveries.incrementAndGet();
            return Attempt.notSent("DEVICE_NOT_RUNNING");
        }
        Optional<AckPayload> acknowledgement;
        try {
            acknowledgement = device.onCommand(command);
        } catch (RuntimeException e) {
            return Attempt.uncertain("DEVICE_FAILED");
        }
        delivered.incrementAndGet();
        acknowledgement.ifPresent(ack -> {
            acknowledgements.incrementAndGet();
            try {
                acknowledgementIntake.submit(ack, context(command.deviceId()));
            } catch (RuntimeException e) {
                // the command was delivered; failing to record the device's answer does not make the delivery unknown
            }
        });
        return Attempt.confirmed(EvidenceKind.SIMULATOR_DELIVERED);
    }
}
