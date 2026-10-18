package com.nexus.adapter.spi;

/** Transport-level identity of the sender (HTTP credentials). MQTT scoping is by topic at the broker and does not declare this. */
public interface DeviceAuthenticator {
    Decoded<AuthenticatedDevice> authenticate(RawMessage message);
}
