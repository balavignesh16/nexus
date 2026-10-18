package com.nexus.adapter.core;

import com.nexus.action.domain.AcknowledgementStatus;
import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.AcknowledgementIntake;
import com.nexus.adapter.spi.IntakeResult;
import com.nexus.adapter.spi.Rejection;
import com.nexus.adapter.spi.TransportContext;
import com.nexus.device.command.DeviceCommandAckMessageHandler;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Hands a decoded acknowledgement to the same application step as the MQTT path ({@link DeviceCommandAckMessageHandler#acknowledge}):
 * first-ACK-wins, the conflict and late-ACK facts and the persisted ACK timeout stay in the core. The device is the authenticated or
 * topic-derived one in the context; an acknowledgement carries no device id of its own.
 */
@Component
public class AcknowledgementIntakeBridge implements AcknowledgementIntake {
    private final DeviceCommandAckMessageHandler handler;
    private final TransportGuard guard;
    private final AdapterMetrics metrics;

    public AcknowledgementIntakeBridge(DeviceCommandAckMessageHandler handler, TransportGuard guard, AdapterMetrics metrics) {
        this.handler = handler;
        this.guard = guard;
        this.metrics = metrics;
    }

    @Override
    public IntakeResult submit(AckPayload payload, TransportContext context) {
        Optional<Rejection> refused = guard.check(context, context.deviceId());
        if (refused.isPresent()) {
            metrics.received(context.adapterId(), "rejected");
            metrics.rejected(context.adapterId(), refused.get().code());
            return new IntakeResult.Refused(refused.get());
        }
        AcknowledgementStatus status = payload.status() == AckPayload.Status.ACCEPTED ? AcknowledgementStatus.ACCEPTED : AcknowledgementStatus.REJECTED;
        handler.acknowledge(context.deviceId(), payload.commandId(), status, payload.deviceTimestamp(), payload.reason());
        metrics.received(context.adapterId(), "accepted");
        return IntakeResult.accepted();
    }
}
