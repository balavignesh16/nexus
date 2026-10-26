package com.nexus.adapter.core;

import com.nexus.adapter.simulator.SimulatorAdapter;
import com.nexus.adapter.spi.Eligibility;
import com.nexus.adapter.spi.TransportKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * The eligibility rule of the in-process simulator transport: eligible while the in-process device is registered and running. The outbox
 * dispatcher is not routed through the adapter egress yet, so an accepted command for a SIMULATOR device would be published over MQTT, the wrong
 * transport. The rule therefore fails closed in this release (no command is accepted for a SIMULATOR device) and the running-device logic
 * is kept, tested, behind one constructor argument for the day the dispatcher is routed.
 */
@Component
@ConditionalOnProperty(name = "nexus.adapter.simulator.enabled", havingValue = "true")
public class SimulatorTransportEligibility implements PerTransportEligibility {
    public static final String DELIVERY_NOT_ROUTED = "DELIVERY_NOT_ROUTED";
    public static final String NOT_ROUTED_MESSAGE = "Command delivery to the in-process simulator is not routed through the outbox in this release";
    public static final String DEVICE_NOT_RUNNING = "DEVICE_NOT_RUNNING";

    private final SimulatorAdapter adapter;
    private final boolean deliveryRouted;

    @Autowired
    public SimulatorTransportEligibility(SimulatorAdapter adapter) {
        this(adapter, false);
    }

    public SimulatorTransportEligibility(SimulatorAdapter adapter, boolean deliveryRouted) {
        this.adapter = adapter;
        this.deliveryRouted = deliveryRouted;
    }

    @Override
    public TransportKind transport() {
        return TransportKind.SIMULATOR;
    }

    @Override
    public Eligibility eligible(UUID deviceId, Instant at) {
        if (!deliveryRouted) return Eligibility.no(DELIVERY_NOT_ROUTED, NOT_ROUTED_MESSAGE);
        if (!adapter.deviceRunning(deviceId)) return Eligibility.no(DEVICE_NOT_RUNNING, "The simulated device is not running");
        return Eligibility.yes();
    }
}
