package com.nexus.adapter.spi;

import java.util.Map;

/**
 * The capabilities an adapter can declare. Each corresponds to exactly one interface of this package; an adapter declares a capability
 * if and only if it implements that interface (the registry verifies it), so the capability list is never a claim without code.
 */
public enum AdapterCapability {
    LIFECYCLE(AdapterLifecycle.class),
    TELEMETRY_DECODER(TelemetryDecoder.class),
    ACKNOWLEDGEMENT_DECODER(AcknowledgementDecoder.class),
    CONNECTIVITY_SOURCE(ConnectivitySource.class),
    COMMAND_EGRESS(CommandEgress.class),
    DEVICE_AUTHENTICATOR(DeviceAuthenticator.class);

    private final Class<?> contract;

    AdapterCapability(Class<?> contract) {
        this.contract = contract;
    }

    public Class<?> contract() {
        return contract;
    }

    public static Map<AdapterCapability, Class<?>> contracts() {
        Map<AdapterCapability, Class<?>> map = new java.util.EnumMap<>(AdapterCapability.class);
        for (AdapterCapability capability : values()) map.put(capability, capability.contract);
        return map;
    }
}
