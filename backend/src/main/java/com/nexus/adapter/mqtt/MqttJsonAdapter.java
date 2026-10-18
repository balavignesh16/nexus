package com.nexus.adapter.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.AcknowledgementDecoder;
import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.AdapterDescriptor;
import com.nexus.adapter.spi.AdapterLifecycle;
import com.nexus.adapter.spi.Attempt;
import com.nexus.adapter.spi.CommandEgress;
import com.nexus.adapter.spi.CommandEnvelope;
import com.nexus.adapter.spi.ConnectivityObservation;
import com.nexus.adapter.spi.ConnectivitySource;
import com.nexus.adapter.spi.Decoded;
import com.nexus.adapter.spi.EvidenceKind;
import com.nexus.adapter.spi.RawMessage;
import com.nexus.adapter.spi.TelemetryDecoder;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.adapter.spi.TransportKind;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The MQTT JSON adapter: today's wire behaviour behind the adapter capabilities. Decoding is {@link MqttJsonDecoder} (extracted from the
 * listener and handlers); command egress puts the stored bytes, verbatim, on the logical topic through the bounded, classified publisher
 * and reports {@link EvidenceKind#BROKER_CONFIRMED}.
 *
 * <p>The single Paho connection, its reconnection and its subscriptions stay owned by {@code MQTTConfiguration},
 * {@code MqttReconnectController} and {@code TelemetryMQTTListener}: one connection carries telemetry, status, acknowledgements and command
 * publication (Tier 5B decision), so this adapter's lifecycle does not open or close it. {@link #start()} and {@link #stop()} only mark the
 * adapter as serving, and the diagnostics report the live connection state.
 */
@Component
public class MqttJsonAdapter implements AdapterDescriptor, AdapterLifecycle, TelemetryDecoder, AcknowledgementDecoder, ConnectivitySource, CommandEgress {
    public static final String ID = "mqtt-json";

    private final MqttJsonDecoder decoder;
    private final MqttPublisher wire;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong telemetryAccepted = new AtomicLong();
    private final AtomicLong telemetryRejected = new AtomicLong();
    private final AtomicLong acknowledgementsAccepted = new AtomicLong();
    private final AtomicLong acknowledgementsRejected = new AtomicLong();
    private final AtomicLong presenceAccepted = new AtomicLong();
    private final AtomicLong presenceRejected = new AtomicLong();
    private final AtomicLong confirmed = new AtomicLong();
    private final AtomicLong notSent = new AtomicLong();
    private final AtomicLong uncertain = new AtomicLong();

    public MqttJsonAdapter(ObjectMapper mapper, Validator validator, MqttPublisher wire) {
        this.decoder = new MqttJsonDecoder(mapper, validator);
        this.wire = wire;
    }

    /** The decoder the telemetry listener uses, so the adapter's counters see the production traffic. */
    public MqttJsonDecoder decoder() {
        return decoder;
    }

    @Override public String id() { return ID; }
    @Override public TransportKind transport() { return TransportKind.MQTT_JSON; }
    @Override public Set<Integer> supportedContractMajors() { return Set.of(1); }

    @Override
    public Set<AdapterCapability> capabilities() {
        return EnumSet.of(AdapterCapability.LIFECYCLE, AdapterCapability.TELEMETRY_DECODER, AdapterCapability.ACKNOWLEDGEMENT_DECODER,
                AdapterCapability.CONNECTIVITY_SOURCE, AdapterCapability.COMMAND_EGRESS);
    }

    @Override
    public Map<String, Long> diagnostics() {
        Map<String, Long> counters = new TreeMap<>();
        counters.put("brokerConnected", wire.connected() ? 1L : 0L);
        counters.put("telemetryAccepted", telemetryAccepted.get());
        counters.put("telemetryRejected", telemetryRejected.get());
        counters.put("acknowledgementsAccepted", acknowledgementsAccepted.get());
        counters.put("acknowledgementsRejected", acknowledgementsRejected.get());
        counters.put("presenceAccepted", presenceAccepted.get());
        counters.put("presenceRejected", presenceRejected.get());
        counters.put("egressConfirmed", confirmed.get());
        counters.put("egressNotSent", notSent.get());
        counters.put("egressUncertain", uncertain.get());
        return counters;
    }

    @Override public void start() { started.set(true); }
    @Override public void stop() { started.set(false); }
    public boolean started() { return started.get(); }

    // ---- decoding

    @Override
    public Decoded<TelemetryPayload> decodeTelemetry(RawMessage message) {
        Decoded<TelemetryPayload> result = decoder.decodeTelemetry(message);
        (result.isAccepted() ? telemetryAccepted : telemetryRejected).incrementAndGet();
        return result;
    }

    @Override
    public Decoded<AckPayload> decodeAcknowledgement(RawMessage message) {
        Decoded<AckPayload> result = decoder.decodeAcknowledgement(message);
        (result.isAccepted() ? acknowledgementsAccepted : acknowledgementsRejected).incrementAndGet();
        return result;
    }

    @Override
    public Decoded<ConnectivityObservation> decodeConnectivity(RawMessage message) {
        Decoded<ConnectivityObservation> result = decoder.decodeConnectivity(message);
        (result.isAccepted() ? presenceAccepted : presenceRejected).incrementAndGet();
        return result;
    }

    // ---- command egress

    @Override
    public Attempt deliver(CommandEnvelope command, Duration timeout) {
        MqttPublisher.Result result = wire.publish(MqttTopics.commands(command.deviceId()), command.payload(), timeout);
        return switch (result.kind()) {
            case CONFIRMED -> {
                confirmed.incrementAndGet();
                yield Attempt.confirmed(EvidenceKind.BROKER_CONFIRMED);
            }
            case NOT_SENT -> {
                notSent.incrementAndGet();
                yield Attempt.notSent(result.code());
            }
            case UNCERTAIN -> {
                uncertain.incrementAndGet();
                yield Attempt.uncertain(result.code());
            }
        };
    }

    /** Counts telemetry the listener decoded through {@link #decoder()} (the listener does not go through the capability method). */
    public void recordTelemetry(boolean accepted) {
        (accepted ? telemetryAccepted : telemetryRejected).incrementAndGet();
    }
}
