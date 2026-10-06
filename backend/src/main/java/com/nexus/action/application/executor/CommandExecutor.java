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
import com.nexus.device.capability.DeviceCapability;
import com.nexus.device.capability.DeviceCapabilityRepository;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.nexus.device.binding.runtime.RuntimeCapabilityResolver;
import com.nexus.device.binding.runtime.CapabilityResolutionSource;
import com.nexus.device.capability.definition.validation.CapabilityOperation;
import com.nexus.device.capability.definition.validation.CapabilityValueValidator;
import com.nexus.device.binding.BindingConstraintService;
import com.nexus.device.capability.definition.CapabilityValueType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tier 5D / Phase 13: publishes a non-retained, QoS-1 MQTT command only
 * after every precondition below passes, in this exact order:
 *
 *   device exists
 *   -> device is not REAL mode (Phase 13 safety guard - see below)
 *   -> authorized WRITE capability
 *   -> command + value shape valid for that capability (CommandValueValidator,
 *      mirrors the Phase 12 simulator's own actuator contract exactly)
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
 * it, or that the actuator actually changed. No physical outcome
 * verification exists.
 *
 * Phase 13 REAL-mode guard: rules introduce unattended, automatic command
 * dispatch for the first time - a misconfigured rule targeting a REAL
 * device would, without this check, dispatch identically to a SIMULATED
 * one. This is a narrow, deliberate safety boundary (not the broader
 * physical-safety architecture Phase 17 will cover) - Phase 16 (Real ESP32
 * Hardware) will revisit it explicitly, not silently. Applies uniformly to
 * both manual and rule-triggered dispatch, since both flow through this
 * same class.
 */
@Component
public class CommandExecutor implements ActionExecutor {

    public record PreparedCommand(UUID deviceId, UUID bindingId, String legacyCapabilityType,
            UUID capabilityDefinitionId, String capabilityKey, CapabilityValueType valueType,
            String unit, String subtargetKey, String wireCapability, String command, Object value) {
        public String targetKey() {
            return bindingId != null ? "binding:" + bindingId : "legacy:" + legacyCapabilityType;
        }
    }

    public static class CommandRejectedException extends RuntimeException {
        public CommandRejectedException(String message) { super(message); }
    }

    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);

    private static final String COMMAND_TOPIC_TEMPLATE = "nexus/devices/%s/commands";
    private static final int COMMAND_QOS = 1;

    private final DeviceRepository deviceRepository;
    private final DeviceCapabilityRepository deviceCapabilityRepository;
    private final DeviceConnectivityRegistry deviceConnectivityRegistry;
    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;
    private final RuntimeCapabilityResolver capabilityResolver;
    private final CapabilityValueValidator capabilityValueValidator;
    private final BindingConstraintService bindingConstraints;

    public CommandExecutor(DeviceRepository deviceRepository,
                            DeviceCapabilityRepository deviceCapabilityRepository,
                            DeviceConnectivityRegistry deviceConnectivityRegistry,
                            MqttClient mqttClient,
                            ObjectMapper objectMapper) {
        this(deviceRepository, deviceCapabilityRepository, deviceConnectivityRegistry, mqttClient, objectMapper, null, null);
    }

    public CommandExecutor(DeviceRepository deviceRepository,
                            DeviceCapabilityRepository deviceCapabilityRepository,
                            DeviceConnectivityRegistry deviceConnectivityRegistry,
                            MqttClient mqttClient,
                            ObjectMapper objectMapper,
                            RuntimeCapabilityResolver capabilityResolver,
                            CapabilityValueValidator capabilityValueValidator) {
        this(deviceRepository, deviceCapabilityRepository, deviceConnectivityRegistry, mqttClient,
                objectMapper, capabilityResolver, capabilityValueValidator, null);
    }

    @Autowired
    public CommandExecutor(DeviceRepository deviceRepository,
                            DeviceCapabilityRepository deviceCapabilityRepository,
                            DeviceConnectivityRegistry deviceConnectivityRegistry,
                            MqttClient mqttClient,
                            ObjectMapper objectMapper,
                            RuntimeCapabilityResolver capabilityResolver,
                            CapabilityValueValidator capabilityValueValidator,
                            BindingConstraintService bindingConstraints) {
        this.deviceRepository = deviceRepository;
        this.deviceCapabilityRepository = deviceCapabilityRepository;
        this.deviceConnectivityRegistry = deviceConnectivityRegistry;
        this.mqttClient = mqttClient;
        this.objectMapper = objectMapper;
        this.capabilityResolver = capabilityResolver;
        this.capabilityValueValidator = capabilityValueValidator;
        this.bindingConstraints = bindingConstraints;
    }

    @Override
    public boolean supports(ActionType type) {
        return type == ActionType.SEND_COMMAND;
    }

    @Override
    public ActionExecutionResult execute(ActionExecutionRequest request, ActionContext context) {
        try {
            return publishPrepared(request, prepare(request));
        } catch (CommandRejectedException e) {
            return failure(request, e.getMessage());
        }
    }

    public PreparedCommand prepare(ActionExecutionRequest request) {
        // context.triggeringEvent()/matchedRule() may be null (a manually
        // triggered command has neither) - deliberately never dereferenced
        // here; this class reads only request.parameters().
        Map<String, Object> parameters = request.parameters() != null ? request.parameters() : Map.of();

        UUID deviceId = parseDeviceId(parameters);
        if (deviceId == null) {
            throw new CommandRejectedException("Missing or invalid deviceId parameter");
        }

        Optional<Device> deviceOpt = deviceRepository.findById(deviceId);
        if (deviceOpt.isEmpty()) {
            throw new CommandRejectedException("Device not found: " + deviceId);
        }
        Device device = deviceOpt.get();

        if (device.getMode() == DeviceMode.REAL) {
            throw new CommandRejectedException("Command dispatch to REAL devices is not yet enabled");
        }

        String capability = asString(parameters.get("capability"));
        String capabilityKey = asString(parameters.get("capabilityKey"));
        boolean genericBindingResolved = false;
        UUID bindingId = null;
        UUID definitionId = null;
        CapabilityValueType valueType = null;
        String effectiveUnit = null;
        String semanticKey = null;
        if (capabilityResolver != null && (capabilityKey != null || capability != null)) {
            try {
                var resolved = capabilityResolver.resolve(deviceId, capabilityKey != null ? capabilityKey : capability, CapabilityDirection.WRITE);
                if (capabilityKey != null && capability != null
                        && !capabilityResolver.resolve(deviceId, capability, CapabilityDirection.WRITE).definition().getId().equals(resolved.definition().getId())) {
                    throw new CommandRejectedException("capability and capabilityKey conflict");
                }
                String command = asString(parameters.get("command"));
                Object value = parameters.get("value");
                var result = capabilityValueValidator.validate(resolved.definition(), value, resolved.effectiveUnit(), CapabilityOperation.COMMAND, command);
                if (!result.isValid()) throw new CommandRejectedException(result.errors().getFirst().message());
                if (bindingConstraints != null && resolved.binding() != null)
                    bindingConstraints.validateValue(resolved.definition(), resolved.binding().getConfiguration(), value);
                if ("relay".equals(resolved.definition().getKey())) {
                    Object configured = resolved.effectiveConstraints().get("channels");
                    Object rawChannel = value instanceof Map<?, ?> map ? map.get("channel") : null;
                    if (!(configured instanceof java.util.List<?> channels)
                            || !(rawChannel instanceof Number channel)
                            || channel.intValue() < 1 || channel.doubleValue() != channel.intValue()
                            || channels.stream().noneMatch(c -> c instanceof Number allowed
                                    && allowed.intValue() == channel.intValue()))
                        throw new CommandRejectedException("RELAY channel is not configured for this binding");
                }
                genericBindingResolved = resolved.resolutionSource() == CapabilityResolutionSource.GENERIC_BINDING;
                bindingId = resolved.binding() == null ? null : resolved.binding().getId();
                definitionId = resolved.definition().getId();
                valueType = resolved.definition().getValueType();
                effectiveUnit = resolved.effectiveUnit();
                semanticKey = resolved.definition().getKey();
                String external = resolved.binding() == null ? null : asString(resolved.binding().getExternalIdentifiers().get("commandCapability"));
                capability = external != null ? external : resolved.definition().getKey().toUpperCase(java.util.Locale.ROOT);
            } catch (RuntimeException exception) {
                throw new CommandRejectedException(exception.getMessage());
            }
        }
        String command = asString(parameters.get("command"));
        Object value = parameters.get("value");
        if (!genericBindingResolved) {
            CapabilityType capabilityType = parseCapabilityType(capability);
            if (capabilityType == null) {
                throw new CommandRejectedException("Unknown capability: " + capability);
            }
            Optional<DeviceCapability> deviceCapability = deviceCapabilityRepository
                    .findByDeviceIdAndCapabilityTypeAndDirection(deviceId, capabilityType, CapabilityDirection.WRITE);
            if (deviceCapability.isEmpty()) {
                throw new CommandRejectedException("Device " + deviceId + " is not authorized for " + capabilityType + " WRITE");
            }
            try {
                CommandValueValidator.validate(capabilityType, deviceCapability.get(), command, value);
            } catch (CommandValueValidator.CommandValidationException e) {
                throw new CommandRejectedException("Unsupported command: capability=" + capability + " command=" + command + " - " + e.getMessage());
            }
            if (valueType == null) valueType = capabilityType == CapabilityType.SERVO ? CapabilityValueType.NUMBER :
                    capabilityType == CapabilityType.RELAY ? CapabilityValueType.OBJECT : CapabilityValueType.BOOLEAN;
            if (semanticKey == null) semanticKey = capabilityType.name().toLowerCase(java.util.Locale.ROOT);
            if (effectiveUnit == null) effectiveUnit = deviceCapability.get().getUnit();
        }

        DeviceConnectivityStatus connectivity = deviceConnectivityRegistry.get(deviceId)
                .map(c -> c.status())
                .orElse(null);
        if (connectivity != DeviceConnectivityStatus.ONLINE) {
            throw new CommandRejectedException("Device is offline");
        }

        if (!mqttClient.isConnected()) {
            throw new CommandRejectedException("MQTT backend is not connected");
        }

        String legacyType = bindingId == null ? capability : null;
        String subtarget = "";
        if ("relay".equals(semanticKey)) {
            if (!(value instanceof Map<?, ?> map) || !(map.get("channel") instanceof Number n)
                    || n.intValue() < 1 || n.doubleValue() != n.intValue())
                throw new CommandRejectedException("RELAY requires a positive integral channel");
            subtarget = "channel:" + n.intValue();
        }
        return new PreparedCommand(deviceId, bindingId, legacyType, definitionId, semanticKey,
                valueType, effectiveUnit, subtarget, capability, command, value);
    }

    public ActionExecutionResult publishPrepared(ActionExecutionRequest request, PreparedCommand prepared) {
        try {
            String topic = String.format(COMMAND_TOPIC_TEMPLATE, prepared.deviceId());
            String payload = buildPayload(request.requestId(), prepared.wireCapability(), prepared.command(), prepared.value());
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
            log.error("Failed to publish command {} for device {}", request.requestId(), prepared.deviceId(), e);
            return failure(request, "MQTT publish failed: " + e.getMessage());
        }
    }

    private String buildPayload(UUID commandId, String capability, String command, Object value) throws Exception {
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
