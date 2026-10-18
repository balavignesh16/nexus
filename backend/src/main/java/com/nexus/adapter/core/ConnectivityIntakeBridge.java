package com.nexus.adapter.core;

import com.nexus.adapter.spi.ConnectivityIntake;
import com.nexus.adapter.spi.ConnectivityObservation;
import com.nexus.adapter.spi.IntakeResult;
import com.nexus.adapter.spi.Rejection;
import com.nexus.adapter.spi.TransportContext;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.connectivity.DeviceStatusMessageHandler;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Hands a decoded presence observation to the same application step as the MQTT status path ({@link DeviceStatusMessageHandler#observe}). */
@Component
public class ConnectivityIntakeBridge implements ConnectivityIntake {
    private final DeviceStatusMessageHandler handler;
    private final TransportGuard guard;
    private final AdapterMetrics metrics;

    public ConnectivityIntakeBridge(DeviceStatusMessageHandler handler, TransportGuard guard, AdapterMetrics metrics) {
        this.handler = handler;
        this.guard = guard;
        this.metrics = metrics;
    }

    @Override
    public IntakeResult submit(ConnectivityObservation observation, TransportContext context) {
        Optional<Rejection> refused = guard.check(context, observation.deviceId());
        if (refused.isPresent()) {
            metrics.received(context.adapterId(), "rejected");
            metrics.rejected(context.adapterId(), refused.get().code());
            return new IntakeResult.Refused(refused.get());
        }
        handler.observe(observation.deviceId(), observation.state() == ConnectivityObservation.State.ONLINE
                ? DeviceConnectivityStatus.ONLINE : DeviceConnectivityStatus.OFFLINE);
        metrics.received(context.adapterId(), "accepted");
        return IntakeResult.accepted();
    }
}
