package com.nexus.adapter.spi;

/** Transport-observed presence (MQTT retained birth and last will). Transports without presence do not declare this capability. */
public interface ConnectivitySource {
    Decoded<ConnectivityObservation> decode(RawMessage message);
}
