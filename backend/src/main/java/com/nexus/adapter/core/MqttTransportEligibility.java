package com.nexus.adapter.core;

import com.nexus.adapter.spi.Eligibility;
import com.nexus.adapter.spi.TransportKind;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * The MQTT eligibility rule, moved here UNCHANGED from the command acceptance guard (adapter-contract.md section 10.8): the device must be
 * ONLINE in the connectivity registry, and the backend's broker client must be connected. The refusal wording is the wording the
 * acceptance guard always used ("Device is offline", "MQTT backend is not connected"), so every existing API response and test is
 * unchanged. It lives in {@code adapter.core} because it reads both the connectivity registry and the broker client.
 */
@Component
public class MqttTransportEligibility implements PerTransportEligibility {
    public static final String OFFLINE_MESSAGE = "Device is offline";
    public static final String BROKER_DISCONNECTED_MESSAGE = "MQTT backend is not connected";

    private final DeviceConnectivityRegistry connectivity;
    private final MqttClient mqttClient;

    public MqttTransportEligibility(DeviceConnectivityRegistry connectivity, MqttClient mqttClient) {
        this.connectivity = connectivity;
        this.mqttClient = mqttClient;
    }

    @Override
    public TransportKind transport() {
        return TransportKind.MQTT_JSON;
    }

    @Override
    public Eligibility eligible(UUID deviceId, Instant at) {
        DeviceConnectivityStatus status = connectivity.get(deviceId).map(c -> c.status()).orElse(null);
        if (status != DeviceConnectivityStatus.ONLINE) {
            return Eligibility.no("DEVICE_OFFLINE", OFFLINE_MESSAGE);
        }
        if (!mqttClient.isConnected()) {
            return Eligibility.no("BROKER_DISCONNECTED", BROKER_DISCONNECTED_MESSAGE);
        }
        return Eligibility.yes();
    }
}
