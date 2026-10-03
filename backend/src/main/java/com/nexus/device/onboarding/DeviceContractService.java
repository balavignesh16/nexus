package com.nexus.device.onboarding;

import com.nexus.device.binding.DeviceCapabilityBindingRepository;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.onboarding.api.DeviceContractResponse;
import com.nexus.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DeviceContractService {
    private final DeviceRepository devices;
    private final DeviceCapabilityBindingRepository bindings;
    private final DeviceOnboardingSessionRepository sessions;

    public DeviceContractService(DeviceRepository devices, DeviceCapabilityBindingRepository bindings,
            DeviceOnboardingSessionRepository sessions) {
        this.devices=devices; this.bindings=bindings; this.sessions=sessions;
    }

    @Transactional(readOnly = true)
    public DeviceContractResponse get(UUID deviceId) {
        var device = devices.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId.toString()));
        String root = "nexus/devices/" + deviceId;
        Map<String,String> topics = Map.of("telemetry", root + "/telemetry", "status", root + "/status",
                "commands", root + "/commands", "commandAck", root + "/commands/ack");
        var capabilities = bindings.findByDeviceId(deviceId).stream().map(binding -> {
            var definition = binding.getDefinition();
            var config = binding.getConfiguration() == null ? Map.<String,Object>of() : binding.getConfiguration();
            Map<String,Object> effective = new LinkedHashMap<>(config);
            if (definition.getObjectConstraints() != null)
                definition.getObjectConstraints().forEach(effective::putIfAbsent);
            BigDecimal min = config.containsKey("minimum") ? new BigDecimal(config.get("minimum").toString()) : definition.getMinimum();
            BigDecimal max = config.containsKey("maximum") ? new BigDecimal(config.get("maximum").toString()) : definition.getMaximum();
            @SuppressWarnings("unchecked")
            List<String> enums = config.containsKey("enumValues") ? (List<String>) config.get("enumValues") : definition.getEnumValues();
            return new DeviceContractResponse.ContractCapability(binding.getId(), definition.getId(),
                    definition.getKey(), definition.getDisplayName(), definition.getDirection(), definition.getValueType(),
                    binding.isEnabled(), binding.getUnit() == null ? definition.getUnit() : binding.getUnit(),
                    definition.getAllowedUnits(), min, max, enums, definition.getSupportedCommands(),
                    text(binding.getExternalIdentifiers(), "telemetryKey"),
                    text(binding.getExternalIdentifiers(), "commandCapability"), Map.copyOf(effective));
        }).toList();
        String version = sessions.findByDeviceId(deviceId).map(DeviceOnboardingSession::getContractVersion).orElse("1.0");
        return new DeviceContractResponse(version, deviceId,
                device.getDeviceTemplate() == null ? null : device.getDeviceTemplate().getId(),
                device.getDeviceTemplateVersion(), device.getMode(), "MQTT", topics, capabilities,
                Map.of("telemetry", Map.of("deviceId", deviceId.toString(), "timestamp", "ISO-8601",
                        "capabilityKey", "<read capability key>", "value", "<typed value>"),
                        "status", "online",
                        "commandAck", Map.of("commandId", "<UUID>", "status", "ACCEPTED", "timestamp", "<epoch seconds>")));
    }

    private static String text(Map<String,Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? null : value.toString();
    }
}
