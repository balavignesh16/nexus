package com.nexus.adapter.core;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.springframework.stereotype.Component;

/** Read-only view of the shared broker connection, for the MQTT adapter's diagnostics. It never publishes and never gates anything. */
@Component
public class BrokerConnectionState {
    private final MqttClient client;

    public BrokerConnectionState(MqttClient client) {
        this.client = client;
    }

    public boolean connected() {
        return client.isConnected();
    }
}
