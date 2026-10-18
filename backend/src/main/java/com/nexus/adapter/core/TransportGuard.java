package com.nexus.adapter.core;

import com.nexus.adapter.spi.Rejection;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TransportContext;
import com.nexus.adapter.spi.TransportKind;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The authorisation rule of adapter-contract.md section 5, applied before a decoded message reaches any intake port: (a) the device's
 * server-side transport assignment must equal the transport of the adapter that decoded the message, then (b) the device id inside
 * the message must equal the authenticated or topic-derived device id. A payload field can never select an adapter. A device with no
 * assignment (unknown) is passed through unchanged: the core already refuses it with its existing wording.
 */
@Component
public class TransportGuard {
    private final TransportAssignments assignments;

    public TransportGuard(TransportAssignments assignments) {
        this.assignments = assignments;
    }

    /** The transport half of the rule alone, for callers whose device identity comes from a topic that they check themselves (MQTT). */
    public Optional<Rejection> checkTransport(UUID deviceId, TransportKind transport) {
        Optional<TransportKind> assigned = assignments.assignedTo(deviceId);
        if (assigned.isPresent() && assigned.get() != transport) {
            return Optional.of(Rejection.permanent(RejectionCode.TRANSPORT_MISMATCH));
        }
        return Optional.empty();
    }

    public Optional<Rejection> check(TransportContext context, UUID payloadDeviceId) {
        Optional<TransportKind> assigned = assignments.assignedTo(context.deviceId());
        if (assigned.isPresent() && assigned.get() != context.transport()) {
            return Optional.of(Rejection.permanent(RejectionCode.TRANSPORT_MISMATCH));
        }
        if (!context.deviceId().equals(payloadDeviceId)) {
            return Optional.of(Rejection.permanent(RejectionCode.DEVICE_MISMATCH));
        }
        return Optional.empty();
    }
}
