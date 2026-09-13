package com.nexus.action.application.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.application.ActionExecutor;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionExecutionResult;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.CapabilityType;
import com.nexus.device.capability.DeviceCapabilityRepository;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.domain.DeviceRepository;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tier 5D: the first genuine physical command dispatch. Publishes a
 * non-retained, QoS-1 MQTT command only after every precondition below
 * passes, in this exact order (approved corrected plan):
 *
 *   device exists
 *   -> authorized WRITE capability
 *   -> command is the one supported (LED, SET_STATE, boolean) case
 *   -> device connectivity == ONLINE (never OFFLINE or UNKNOWN)
 *   -> backend MQTT client connected
 *   -> publish
 *
 * An OFFLINE or UNKNOWN device fails immediately, before any MQTT publish
 * is attempted - commands are never queued, never retained, and NEXUS
 * never relies on broker-side persistence to deliver one later.
 *
 * This class can only ever claim "NEXUS successfully published the
 * command to MQTT" - it never claims the device received it, processed
 * it, or that the LED actually changed. No acknowledgement or physical
 * outcome verification exists yet (deferred to a later phase).
 */
@Component
public class CommandExecutor implements ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);

    private static final String COMMAND_TOPIC_TEMPLATE = "nexus/devices/%s/commands";
    private static final int COMMAND_QOS = 1;

    private static final String SUPPORTED_CAPABILITY = "LED";
    private static final String SUPPORTED_COMMAND = "SET_STATE";

    private final DeviceRepository deviceRepository;
    private final DeviceCapabilityRepository deviceCapabilityRepository;
    private final DeviceConnectivityRegistry deviceConnectivityRegistry;
    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;

    public CommandExecutor(DeviceRepository deviceRepository,
                            DeviceCapabilityRepository deviceCapabilityRepository,
                            DeviceConnectivityRegistry deviceConnectivityRegistry,
                            MqttClient mqttClient,
                            ObjectMapper objectMapper) {
        this.deviceRepository = deviceRepository;
        this.deviceCapabilityRepository = deviceCapabilityRepository;
        this.deviceConnectivityRegistry = deviceConnectivityRegistry;
        this.mqttClient = mqttClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ActionType type) {
        return type == ActionType.SEND_COMMAND;
    }

    @Override
    public ActionExecutionResult execute(ActionExecutionRequest request, ActionContext context) {
        // context.triggeringEvent()/matchedRule() may be null (a manually
        // triggered command has neither) - deliberately never dereferenced
        // here; this class reads only request.parameters().
        Map<String, Object> parameters = request.parameters() != null ? request.parameters() : Map.of();

        UUID deviceId = parseDeviceId(parameters);
        if (deviceId == null) {
            return failure(request, "Missing or invalid deviceId parameter");
        }

        if (!deviceRepository.existsById(deviceId)) {
            return failure(request, "Device not found: " + deviceId);
        }

        String capability = asString(parameters.get("capability"));
        CapabilityType capabilityType = parseCapabilityType(capability);
        if (capabilityType == null) {
            return failure(request, "Unknown capability: " + capability);
        }

        if (!deviceCapabilityRepository.existsByDeviceIdAndCapabilityTypeAndDirection(deviceId, capabilityType, CapabilityDirection.WRITE)) {
            return failure(request, "Device " + deviceId + " is not authorized for " + capabilityType + " WRITE");
        }

        String command = asString(parameters.get("command"));
        Object valueObj = parameters.get("value");
        if (!isSupportedCommand(capability, command, valueObj)) {
            return failure(request, "Unsupported command: capability=" + capability + " command=" + command);
        }
        boolean value = (Boolean) valueObj;

        DeviceConnectivityStatus connectivity = deviceConnectivityRegistry.get(deviceId)
                .map(c -> c.status())
                .orElse(null);
        if (connectivity != DeviceConnectivityStatus.ONLINE) {
            return failure(request, "Device is offline");
        }

        if (!mqttClient.isConnected()) {
            return failure(request, "MQTT backend is not connected");
        }

        try {
            String topic = String.format(COMMAND_TOPIC_TEMPLATE, deviceId);
            String payload = buildPayload(request.requestId(), capability, command, value);
            MqttMessage message = new MqttMessage(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            message.setQos(COMMAND_QOS);
            message.setRetained(false);
            mqttClient.publish(topic, message);

            log.info("Published command {} to {}: {}", request.requestId(), topic, payload);
            return new ActionExecutionResult(
                    request.requestId(),
                    request,
                    ExecutionStatus.SUCCESS,
                    Instant.now(),
                    "Command published to MQTT"
            );
        } catch (Exception e) {
            log.error("Failed to publish command {} for device {}", request.requestId(), deviceId, e);
            return failure(request, "MQTT publish failed: " + e.getMessage());
        }
    }

    private boolean isSupportedCommand(String capability, String command, Object valueObj) {
        return SUPPORTED_CAPABILITY.equals(capability)
                && SUPPORTED_COMMAND.equals(command)
                && valueObj instanceof Boolean;
    }

    private String buildPayload(UUID commandId, String capability, String command, boolean value) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandId", commandId.toString());
        payload.put("capability", capability);
        payload.put("command", command);
        payload.put("value", value);
        payload.put("timestamp", Instant.now().getEpochSecond());
        return objectMapper.writeValueAsString(payload);
    }

    private UUID parseDeviceId(Map<String, Object> parameters) {
        Object raw = parameters.get("deviceId");
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private CapabilityType parseCapabilityType(String capability) {
        if (capability == null) {
            return null;
        }
        try {
            return CapabilityType.valueOf(capability);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private ActionExecutionResult failure(ActionExecutionRequest request, String message) {
        log.warn("Command rejected for request {}: {}", request.requestId(), message);
        return new ActionExecutionResult(
                request.requestId(),
                request,
                ExecutionStatus.FAILURE,
                Instant.now(),
                message
        );
    }
}
