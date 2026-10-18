package com.nexus.adapter.core;

import com.nexus.adapter.spi.IntakeResult;
import com.nexus.adapter.spi.Rejection;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryIntake;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.adapter.spi.TransportContext;
import com.nexus.device.onboarding.OnboardingTelemetryRejectedException;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.InvalidTelemetryException;
import com.nexus.telemetry.application.TelemetryMessageConflictException;
import com.nexus.telemetry.application.TelemetryService;
import com.nexus.telemetry.application.TelemetrySource;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Hands decoded telemetry to the UNCHANGED {@link TelemetryService#processTelemetry(TelemetryRequest, TelemetrySource)}. Everything the core
 * decides (device existence, onboarding, quality, normalisation, Twin and rules, the messageId inbox and its conflict semantics) stays
 * there. The three deterministic permanent refusals are reported as fixed codes; any other failure propagates so the adapter does not
 * acknowledge the message and the transport can redeliver it.
 */
@Component
public class TelemetryIntakeBridge implements TelemetryIntake {
    private final TelemetryService telemetry;
    private final TransportGuard guard;
    private final AdapterMetrics metrics;

    public TelemetryIntakeBridge(TelemetryService telemetry, TransportGuard guard, AdapterMetrics metrics) {
        this.telemetry = telemetry;
        this.guard = guard;
        this.metrics = metrics;
    }

    @Override
    public IntakeResult submit(TelemetryPayload payload, TransportContext context) {
        Optional<Rejection> refused = guard.check(context, payload.deviceId());
        if (refused.isPresent()) return refuse(context, refused.get());
        TelemetryRequest request = new TelemetryRequest(payload.deviceId(), payload.timestamp(), payload.sensorType(), payload.value(),
                payload.unit(), payload.capabilityKey(), payload.readbackCommandId(), payload.messageId());
        try {
            telemetry.processTelemetry(request, source(context));
        } catch (OnboardingTelemetryRejectedException e) {
            return refuse(context, Rejection.permanent(RejectionCode.ONBOARDING_REJECTED));
        } catch (InvalidTelemetryException e) {
            return refuse(context, Rejection.permanent(RejectionCode.INVALID_TELEMETRY));
        } catch (TelemetryMessageConflictException e) {
            return refuse(context, Rejection.permanent(RejectionCode.DUPLICATE_CONFLICT));
        }
        metrics.received(context.adapterId(), "accepted");
        return IntakeResult.accepted();
    }

    /**
     * The label is diagnostic only and is stored by the inbox under a CHECK of ('MQTT', 'HTTP') (V40), so the in-process simulator,
     * which publishes through the MQTT path today, keeps the MQTT label until a migration widens the constraint.
     */
    static TelemetrySource source(TransportContext context) {
        return switch (context.transport()) {
            case HTTP_WEBHOOK -> TelemetrySource.HTTP;
            case MQTT_JSON, SIMULATOR -> TelemetrySource.MQTT;
        };
    }

    private IntakeResult refuse(TransportContext context, Rejection rejection) {
        metrics.received(context.adapterId(), "rejected");
        metrics.rejected(context.adapterId(), rejection.code());
        return new IntakeResult.Refused(rejection);
    }
}
