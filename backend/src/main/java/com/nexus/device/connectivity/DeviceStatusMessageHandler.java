package com.nexus.device.connectivity;

import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.onboarding.OnboardingLifecycleService;
import com.nexus.realtime.RealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Tier 5B: all device-connectivity-status logic lives here, isolated from
 * TelemetryMQTTListener (which only routes messages on this topic to
 * {@link #handle(String, byte[])} and otherwise remains unaware of what
 * this class does). This class never touches telemetry persistence, the
 * Digital Twin, DeviceStatus, the Rule Engine, the Action Engine, or the
 * AI system - it does exactly one thing: durably record and broadcast
 * physical MQTT connectivity.
 *
 * ACK/error contract (mirrors, but is independent of, TelemetryMQTTListener's
 * own): a permanently invalid message (malformed topic, unrecognized
 * payload, unknown device) is logged and this method returns normally -
 * the caller acknowledges it, exactly like telemetry's "ack and drop"
 * category. A genuine transient failure (e.g. the repository throws) is
 * NOT caught here - it propagates so the caller does not acknowledge,
 * allowing QoS 1 redelivery to retry later.
 */
@Component
public class DeviceStatusMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceStatusMessageHandler.class);

    private final DeviceRepository deviceRepository;
    private final DeviceConnectivityRegistry connectivityRegistry;
    private final RealtimePublisher realtimePublisher;
    private final OnboardingLifecycleService onboarding;

    public DeviceStatusMessageHandler(DeviceRepository deviceRepository,
                                       DeviceConnectivityRegistry connectivityRegistry,
                                       RealtimePublisher realtimePublisher) {
        this(deviceRepository, connectivityRegistry, realtimePublisher, null);
    }

    @Autowired
    public DeviceStatusMessageHandler(DeviceRepository deviceRepository,
                                       DeviceConnectivityRegistry connectivityRegistry,
                                       RealtimePublisher realtimePublisher,
                                       OnboardingLifecycleService onboarding) {
        this.deviceRepository = deviceRepository;
        this.connectivityRegistry = connectivityRegistry;
        this.realtimePublisher = realtimePublisher;
        this.onboarding = onboarding;
    }

    public void handle(String topic, byte[] payload) {
        UUID deviceId = parseDeviceIdOrNull(topic);
        if (deviceId == null) {
            log.warn("Malformed device status topic '{}'. Acknowledging and dropping (not retryable).", topic);
            return;
        }

        DeviceConnectivityStatus status = parseStatusOrNull(payload);
        if (status == null) {
            log.warn("Unrecognized device status payload on topic {}: '{}'. Acknowledging and dropping (not retryable).",
                    topic, safePayloadText(payload));
            return;
        }
        apply(deviceId, status);
    }

    /**
     * P5.2: applies a presence observation that an adapter has already decoded (the adapter intake port). It is the SAME application step
     * as {@link #handle}, reached without the MQTT topic and plain-text parsing.
     */
    public void observe(UUID deviceId, DeviceConnectivityStatus status) {
        apply(deviceId, status);
    }

    private void apply(UUID deviceId, DeviceConnectivityStatus status) {
        // Validated first, exactly like TelemetryMQTTListener's device-existence
        // gate - an unknown device is permanently invalid input, never a
        // reason to create a Device (no automatic discovery/registration).
        if (!deviceRepository.existsById(deviceId)) {
            log.warn("Device status message for unknown device {}. Acknowledging and dropping (not retryable); no Device is created.", deviceId);
            return;
        }

        boolean changed = connectivityRegistry.applyObservedStatus(deviceId, status);
        if (status == DeviceConnectivityStatus.ONLINE && onboarding != null) onboarding.observedOnline(deviceId);
        if (changed) {
            realtimePublisher.publishDeviceConnectivity(deviceId, status.name());
            log.debug("Device {} connectivity changed to {}", deviceId, status);
        } else {
            log.debug("Device {} connectivity observation {} is a duplicate of the current state; no-op.", deviceId, status);
        }
    }

    private UUID parseDeviceIdOrNull(String topic) {
        String[] parts = topic.split("/");
        // Expected shape: nexus/devices/{deviceId}/status -> 4 segments.
        if (parts.length != 4 || !"status".equals(parts[3])) {
            return null;
        }
        try {
            return UUID.fromString(parts[2]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private DeviceConnectivityStatus parseStatusOrNull(byte[] payload) {
        String text = safePayloadText(payload).trim();
        if ("online".equals(text)) {
            return DeviceConnectivityStatus.ONLINE;
        }
        if ("offline".equals(text)) {
            return DeviceConnectivityStatus.OFFLINE;
        }
        return null;
    }

    private String safePayloadText(byte[] payload) {
        return payload == null ? "" : new String(payload, StandardCharsets.UTF_8);
    }
}
