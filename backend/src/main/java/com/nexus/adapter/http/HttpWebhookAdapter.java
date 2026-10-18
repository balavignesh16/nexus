package com.nexus.adapter.http;

import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.AcknowledgementDecoder;
import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.AdapterDescriptor;
import com.nexus.adapter.spi.AdapterLifecycle;
import com.nexus.adapter.spi.AuthenticatedDevice;
import com.nexus.adapter.spi.Decoded;
import com.nexus.adapter.spi.DeviceAuthenticator;
import com.nexus.adapter.spi.RawMessage;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryDecoder;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.adapter.spi.TransportKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The HTTP/webhook adapter (ingest only in this release): it authenticates a device by its own credential, decodes telemetry and
 * acknowledgements with the strict HTTP contract, and accepts a posted status as a device claim. It declares NO {@code CommandEgress}: command
 * delivery over HTTP (the pull design of contract section 10) is deferred until its safety proof exists, so the conformance report says
 * "command egress: not supported" and nothing here can put a command anywhere.
 *
 * <p>It exists only when {@code nexus.adapter.http.enabled=true}; without the device-key pepper it refuses to start (fail closed). Its
 * lifecycle only gates acceptance (a stopped adapter answers 503); the servlet endpoints are owned by the web server.
 */
@Component
@ConditionalOnProperty(name = "nexus.adapter.http.enabled", havingValue = "true")
public class HttpWebhookAdapter implements AdapterDescriptor, AdapterLifecycle, TelemetryDecoder, AcknowledgementDecoder, DeviceAuthenticator {
    public static final String ID = "http-webhook";
    public static final String AUTHORIZATION = "authorization";
    public static final String VERSION_HEADER = "x-nexus-contract-version";
    private static final byte[] DUMMY_HASH = new byte[32];

    /** The outcome of presenting a credential. {@code failure} is null on success; it is only ever UNAUTHENTICATED or FORBIDDEN_DEVICE. */
    public record Authentication(RejectionCode failure, CredentialStore.Stored credential) {
        public boolean ok() { return failure == null; }
    }

    private final CredentialStore store;
    private final KeyHasher hasher;
    private final HttpJsonDecoder decoder = new HttpJsonDecoder();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong authenticationFailures = new AtomicLong();

    @Autowired
    public HttpWebhookAdapter(CredentialStore store, Environment environment) {
        this.store = store;
        this.hasher = KeyHasher.fromEnvironment(environment::getProperty);
    }

    public HttpWebhookAdapter(CredentialStore store, KeyHasher hasher) {
        this.store = store;
        this.hasher = hasher;
    }

    public HttpJsonDecoder decoder() { return decoder; }
    public boolean running() { return running.get(); }

    @Override public String id() { return ID; }
    @Override public TransportKind transport() { return TransportKind.HTTP_WEBHOOK; }
    @Override public Set<Integer> supportedContractMajors() { return Set.of(1); }

    @Override
    public Set<AdapterCapability> capabilities() {
        return EnumSet.of(AdapterCapability.LIFECYCLE, AdapterCapability.TELEMETRY_DECODER, AdapterCapability.ACKNOWLEDGEMENT_DECODER, AdapterCapability.DEVICE_AUTHENTICATOR);
    }

    @Override
    public Map<String, Long> diagnostics() {
        Map<String, Long> counters = new TreeMap<>();
        counters.put("accepted", accepted.get());
        counters.put("rejected", rejected.get());
        counters.put("authenticationFailures", authenticationFailures.get());
        return counters;
    }

    @Override public void start() { running.set(true); }
    @Override public void stop() { running.set(false); }

    public void countAccepted() { accepted.incrementAndGet(); }
    public void countRejected() { rejected.incrementAndGet(); }

    // ---- authentication

    /**
     * Verifies a presented {@code Authorization: Bearer nxk1_...} header. The presented secret is always hashed and compared in constant time,
     * even when the lookup id is unknown or the header is malformed (against a dummy hash), so response time does not reveal which credentials
     * exist. A revoked credential, a retiring one at or after its retire instant (judged by the database clock) and an unknown one are all the
     * same generic failure.
     */
    public Authentication authenticateHeader(String authorization) {
        String secret = "";
        Optional<KeyFormat.Parsed> parsed = Optional.empty();
        if (authorization != null && authorization.startsWith("Bearer ")) {
            parsed = KeyFormat.parse(authorization.substring("Bearer ".length()));
            if (parsed.isPresent()) secret = parsed.get().secret();
        }
        Optional<CredentialStore.Stored> stored = parsed.flatMap(p -> store.find(p.lookupId()));
        boolean match = hasher.matches(stored.map(CredentialStore.Stored::secretHash).orElse(DUMMY_HASH), secret);
        if (parsed.isEmpty() || stored.isEmpty() || !match || !KeyHasher.ALGORITHM.equals(stored.get().algorithm())) return failed(RejectionCode.UNAUTHENTICATED);
        CredentialStore.Stored credential = stored.get();
        Instant now = credential.databaseNow();
        switch (credential.status()) {
            case "ACTIVE" -> { }
            case "RETIRING" -> {
                if (credential.retireAt() == null || !now.isBefore(credential.retireAt())) return failed(RejectionCode.UNAUTHENTICATED);
            }
            default -> {
                return failed(RejectionCode.UNAUTHENTICATED);
            }
        }
        if (!"ACTIVE".equals(credential.deviceStatus())) return new Authentication(RejectionCode.FORBIDDEN_DEVICE, credential);
        return new Authentication(null, credential);
    }

    private Authentication failed(RejectionCode code) {
        authenticationFailures.incrementAndGet();
        return new Authentication(code, null);
    }

    @Override
    public Decoded<AuthenticatedDevice> authenticate(RawMessage message) {
        Authentication result = authenticateHeader(message.metadata().get(AUTHORIZATION));
        if (!result.ok()) return Decoded.refused(result.failure());
        return Decoded.accepted(new AuthenticatedDevice(result.credential().deviceId(), result.credential().lookupId()));
    }

    // ---- decoding

    @Override
    public Decoded<TelemetryPayload> decodeTelemetry(RawMessage message) {
        return decoder.decodeTelemetry(message.payload(), message.metadata().get(VERSION_HEADER));
    }

    @Override
    public Decoded<AckPayload> decodeAcknowledgement(RawMessage message) {
        return decoder.decodeAcknowledgement(message.payload(), message.metadata().get(VERSION_HEADER));
    }
}
