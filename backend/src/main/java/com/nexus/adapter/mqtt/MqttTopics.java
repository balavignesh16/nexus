package com.nexus.adapter.mqtt;

import java.util.Optional;
import java.util.UUID;

/** The MQTT topic layout of the wire contract (adapter-contract.md section 7): {@code nexus/devices/<deviceId>/{telemetry|status|commands|commands/ack}}. */
public final class MqttTopics {
    private MqttTopics() {}

    public static String commands(UUID deviceId) {
        return "nexus/devices/" + deviceId + "/commands";
    }

    /** The device segment of a telemetry topic; the legacy rule only requires three segments, so this does not check the suffix. */
    public static Optional<String> telemetryDeviceSegment(String topic) {
        String[] parts = topic.split("/");
        return parts.length >= 3 ? Optional.of(parts[2]) : Optional.empty();
    }

    /** {@code nexus/devices/<uuid>/status} (exactly four segments). */
    public static Optional<UUID> statusDevice(String topic) {
        String[] parts = topic.split("/");
        if (parts.length != 4 || !"status".equals(parts[3])) return Optional.empty();
        return uuid(parts[2]);
    }

    /** {@code nexus/devices/<uuid>/commands/ack} (exactly five segments). */
    public static Optional<UUID> ackDevice(String topic) {
        String[] parts = topic.split("/");
        if (parts.length != 5 || !"commands".equals(parts[3]) || !"ack".equals(parts[4])) return Optional.empty();
        return uuid(parts[2]);
    }

    /** The device of a telemetry, status or ack topic, whichever it is; used to scope the transport check. */
    public static Optional<UUID> anyDevice(String topic) {
        if (topic.endsWith("/status")) return statusDevice(topic);
        if (topic.endsWith("/commands/ack")) return ackDevice(topic);
        return telemetryDeviceSegment(topic).flatMap(MqttTopics::uuid);
    }

    private static Optional<UUID> uuid(String text) {
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
