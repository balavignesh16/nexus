package com.nexus.adapter.http;

import com.nexus.adapter.core.AdapterMetrics;
import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.AcknowledgementIntake;
import com.nexus.adapter.spi.Decoded;
import com.nexus.adapter.spi.IntakeResult;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryIntake;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.adapter.spi.TransportContext;
import com.nexus.adapter.spi.TransportKind;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Device-authenticated ingest over HTTP (adapter-contract.md section 9). Three POST endpoints, all under {@code /api/v1/device-adapter/devices/{deviceId}}:
 * {@code /telemetry}, {@code /acknowledgements} and {@code /status}. The credential travels only in {@code Authorization: Bearer}; nothing here
 * logs, echoes or stores it. A request is processed in a fixed order, each step a hard stop: adapter running, TLS guard, declared size, content
 * type, credential, device and transport authorisation, per-device concurrency, bounded body read, strict decoding, identity (path, credential
 * and payload name one device), the unchanged core through the intake port. Every answer is a fixed code; nothing that the caller sent is
 * reflected. Infrastructure failures answer 503 and acknowledge nothing, so the device retries (telemetry is idempotent by messageId).
 *
 * <p>There is no endpoint here, and none anywhere in the application, that creates, rotates, revokes or lists credentials (D7).
 */
@RestController
@RequestMapping("/api/v1/device-adapter")
@ConditionalOnProperty(name = "nexus.adapter.http.enabled", havingValue = "true")
public class HttpIngestController {
    private enum Kind { TELEMETRY, ACKNOWLEDGEMENT, STATUS }

    private final HttpWebhookAdapter adapter;
    private final HttpAdapterSettings settings;
    private final TelemetryIntake telemetryIntake;
    private final AcknowledgementIntake acknowledgementIntake;
    private final CredentialStore store;
    private final AdapterMetrics metrics;
    private final PerDeviceLimiter limiter;

    public HttpIngestController(HttpWebhookAdapter adapter, HttpAdapterSettings settings, TelemetryIntake telemetryIntake,
                                AcknowledgementIntake acknowledgementIntake, CredentialStore store, AdapterMetrics metrics) {
        this.adapter = adapter;
        this.settings = settings;
        this.telemetryIntake = telemetryIntake;
        this.acknowledgementIntake = acknowledgementIntake;
        this.store = store;
        this.metrics = metrics;
        this.limiter = new PerDeviceLimiter(settings.perDeviceConcurrency());
    }

    @PostMapping("/devices/{deviceId}/telemetry")
    public ResponseEntity<Map<String, String>> telemetry(@PathVariable String deviceId, HttpServletRequest request) {
        return handle(Kind.TELEMETRY, deviceId, request);
    }

    @PostMapping("/devices/{deviceId}/acknowledgements")
    public ResponseEntity<Map<String, String>> acknowledgements(@PathVariable String deviceId, HttpServletRequest request) {
        return handle(Kind.ACKNOWLEDGEMENT, deviceId, request);
    }

    @PostMapping("/devices/{deviceId}/status")
    public ResponseEntity<Map<String, String>> status(@PathVariable String deviceId, HttpServletRequest request) {
        return handle(Kind.STATUS, deviceId, request);
    }

    private ResponseEntity<Map<String, String>> handle(Kind kind, String pathDevice, HttpServletRequest request) {
        if (!adapter.running()) return answer(HttpStatus.SERVICE_UNAVAILABLE, "ADAPTER_STOPPED");
        if (settings.requireForwardedHttps() && !"https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))) {
            return reject(HttpStatus.FORBIDDEN, "TLS_REQUIRED", null);
        }
        long declared = request.getContentLengthLong();
        if (declared > settings.maxBodyBytes()) return reject(HttpStatus.PAYLOAD_TOO_LARGE, RejectionCode.PAYLOAD_TOO_LARGE.name(), RejectionCode.PAYLOAD_TOO_LARGE);
        if (!contentTypeAllowed(kind, request.getContentType())) return reject(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", null);

        HttpWebhookAdapter.Authentication authentication = adapter.authenticateHeader(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (!authentication.ok()) {
            if (authentication.failure() == RejectionCode.UNAUTHENTICATED) {
                metrics.authFailure(RejectionCode.UNAUTHENTICATED);
                ResponseEntity<Map<String, String>> body = answer(HttpStatus.UNAUTHORIZED, RejectionCode.UNAUTHENTICATED.name());
                return ResponseEntity.status(body.getStatusCode()).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").body(body.getBody());
            }
            return reject(HttpStatus.FORBIDDEN, RejectionCode.FORBIDDEN_DEVICE.name(), RejectionCode.FORBIDDEN_DEVICE);
        }
        CredentialStore.Stored credential = authentication.credential();
        UUID pathId = parse(pathDevice);
        if (pathId == null || !pathId.equals(credential.deviceId())) return reject(HttpStatus.FORBIDDEN, RejectionCode.FORBIDDEN_DEVICE.name(), RejectionCode.FORBIDDEN_DEVICE);
        if (!TransportKind.HTTP_WEBHOOK.name().equals(credential.transport())) {
            return reject(HttpStatus.FORBIDDEN, RejectionCode.TRANSPORT_MISMATCH.name(), RejectionCode.TRANSPORT_MISMATCH);
        }

        if (!limiter.tryAcquire(pathId)) return answer(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS");
        try {
            byte[] body = readBounded(request);
            if (body == null) return reject(HttpStatus.PAYLOAD_TOO_LARGE, RejectionCode.PAYLOAD_TOO_LARGE.name(), RejectionCode.PAYLOAD_TOO_LARGE);
            String version = request.getHeader("X-Nexus-Contract-Version");
            TransportContext context = new TransportContext(HttpWebhookAdapter.ID, TransportKind.HTTP_WEBHOOK, pathId, Instant.now(), "http");
            return switch (kind) {
                case TELEMETRY -> telemetry(body, version, pathId, context, credential);
                case ACKNOWLEDGEMENT -> acknowledgement(body, version, context, credential);
                case STATUS -> statusClaim(body, credential);
            };
        } catch (IOException e) {
            return answer(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE");
        } catch (RuntimeException e) {
            // an infrastructure failure of the core or the database: nothing was acknowledged, the device retries
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5").body(Map.of("error", "UNAVAILABLE"));
        } finally {
            limiter.release(pathId);
        }
    }

    private ResponseEntity<Map<String, String>> telemetry(byte[] body, String version, UUID pathId, TransportContext context, CredentialStore.Stored credential) {
        Decoded<TelemetryPayload> decoded = adapter.decoder().decodeTelemetry(body, version);
        if (decoded instanceof Decoded.Refused<TelemetryPayload> refused) return refuse(refused.rejection().code());
        TelemetryPayload payload = ((Decoded.Accepted<TelemetryPayload>) decoded).value();
        if (!payload.deviceId().equals(pathId)) return reject(HttpStatus.FORBIDDEN, RejectionCode.FORBIDDEN_DEVICE.name(), RejectionCode.FORBIDDEN_DEVICE);
        store.recordContact(credential.credentialId(), credential.deviceId());
        IntakeResult result = telemetryIntake.submit(payload, context);
        return conclude(result);
    }

    private ResponseEntity<Map<String, String>> acknowledgement(byte[] body, String version, TransportContext context, CredentialStore.Stored credential) {
        Decoded<AckPayload> decoded = adapter.decoder().decodeAcknowledgement(body, version);
        if (decoded instanceof Decoded.Refused<AckPayload> refused) return refuse(refused.rejection().code());
        store.recordContact(credential.credentialId(), credential.deviceId());
        return conclude(acknowledgementIntake.submit(((Decoded.Accepted<AckPayload>) decoded).value(), context));
    }

    /** A posted status is a device claim: valid text counts as a contact and is acknowledged; it never drives the connectivity registry. */
    private ResponseEntity<Map<String, String>> statusClaim(byte[] body, CredentialStore.Stored credential) {
        Decoded<HttpJsonDecoder.StatusClaim> decoded = adapter.decoder().decodeStatus(body);
        if (decoded instanceof Decoded.Refused<HttpJsonDecoder.StatusClaim> refused) return refuse(refused.rejection().code());
        store.recordContact(credential.credentialId(), credential.deviceId());
        adapter.countAccepted();
        metrics.received(HttpWebhookAdapter.ID, "accepted");
        return ResponseEntity.ok(Map.of("result", "RECORDED"));
    }

    private ResponseEntity<Map<String, String>> conclude(IntakeResult result) {
        if (result instanceof IntakeResult.Refused refused) {
            adapter.countRejected();
            return answer(statusOf(refused.rejection().code()), refused.rejection().code().name());
        }
        adapter.countAccepted();
        return ResponseEntity.ok(Map.of("result", "ACCEPTED"));
    }

    private ResponseEntity<Map<String, String>> refuse(RejectionCode code) {
        return reject(statusOf(code), code.name(), code);
    }

    /** The complete, stable mapping from a fixed rejection code to an HTTP status. */
    static HttpStatus statusOf(RejectionCode code) {
        return switch (code) {
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN_DEVICE, TRANSPORT_MISMATCH, DEVICE_MISMATCH, TOPIC_DEVICE_MISMATCH -> HttpStatus.FORBIDDEN;
            case PAYLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case DUPLICATE_CONFLICT -> HttpStatus.CONFLICT;
            case ONBOARDING_REJECTED, INVALID_TELEMETRY -> HttpStatus.UNPROCESSABLE_ENTITY;
            case MALFORMED_JSON, INVALID_FIELDS, INVALID_MESSAGE_ID, INVALID_LEGACY_PAYLOAD, INVALID_CONTRACT_VERSION, CONTRACT_VERSION_CONFLICT,
                 UNSUPPORTED_CONTRACT_MAJOR, UNSUPPORTED_REQUIRED_FEATURE -> HttpStatus.BAD_REQUEST;
        };
    }

    private ResponseEntity<Map<String, String>> reject(HttpStatus status, String error, RejectionCode code) {
        adapter.countRejected();
        metrics.received(HttpWebhookAdapter.ID, "rejected");
        if (code != null) metrics.rejected(HttpWebhookAdapter.ID, code);
        return answer(status, error);
    }

    private static ResponseEntity<Map<String, String>> answer(HttpStatus status, String error) {
        return ResponseEntity.status(status).body(Map.of("error", error));
    }

    private static boolean contentTypeAllowed(Kind kind, String contentType) {
        if (contentType == null) return false;
        MediaType type;
        try {
            type = MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return false;
        }
        String wanted = kind == Kind.STATUS ? "text/plain" : "application/json";
        if (!type.getType().toLowerCase(Locale.ROOT).concat("/").concat(type.getSubtype().toLowerCase(Locale.ROOT)).equals(wanted)) return false;
        return type.getCharset() == null || type.getCharset().name().equalsIgnoreCase("UTF-8");
    }

    private static UUID parse(String text) {
        try {
            return text.length() == 36 ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Reads at most the limit; a body that is longer (a lying or absent Content-Length) is refused without being parsed. */
    private byte[] readBounded(HttpServletRequest request) throws IOException {
        int max = settings.maxBodyBytes();
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(max + 1);
            return bytes.length > max ? null : bytes;
        }
    }
}
