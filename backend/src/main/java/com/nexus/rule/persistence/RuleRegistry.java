package com.nexus.rule.persistence;

import com.nexus.action.application.executor.CommandValueValidator;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.CapabilityType;
import com.nexus.device.capability.DeviceCapability;
import com.nexus.device.capability.DeviceCapabilityRepository;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.rule.domain.CapabilityValueKind;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleOperator;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.nexus.rule.application.RuleCapabilityValidator;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RuleRegistry {

    private final ConcurrentHashMap<UUID, Rule> rules = new ConcurrentHashMap<>();
    private final RuleRepository repository;
    private final DeviceCapabilityRepository deviceCapabilityRepository;
    private final DeviceRepository deviceRepository;
    private final RuleCapabilityValidator genericValidator;

    public RuleRegistry(RuleRepository repository, DeviceCapabilityRepository deviceCapabilityRepository,
                         DeviceRepository deviceRepository) {
        this(repository,deviceCapabilityRepository,deviceRepository,null);
    }
    @Autowired
    public RuleRegistry(RuleRepository repository, DeviceCapabilityRepository deviceCapabilityRepository,
                         DeviceRepository deviceRepository,RuleCapabilityValidator genericValidator) {
        this.repository = repository;
        this.deviceCapabilityRepository = deviceCapabilityRepository;
        this.deviceRepository = deviceRepository;
        this.genericValidator = genericValidator;
    }

    /**
     * Hydrates the in-memory registry from the database at startup
     * (Tier 3 Phase 7). Deliberately has no try/catch: if the database
     * cannot be read, or a persisted rule cannot be deserialized, this
     * exception must propagate and fail application startup rather than
     * let NEXUS silently start with an incomplete or empty rule set -
     * mirroring InMemoryDigitalTwinRegistry.init()'s existing, already
     * fail-closed hydration behavior. An empty rules table is a distinct,
     * valid case (a fresh install) and correctly results in an empty
     * registry, not a failure.
     */
    @PostConstruct
    public void init() {
        for (RuleEntity entity : repository.findAll()) {
            rules.put(entity.ruleId(), entity.toDomain());
        }
    }

    public void register(Rule rule) {
        validate(rule);
        persistAndCache(rule);
    }

    public void unregister(UUID ruleId) {
        repository.deleteById(ruleId);
        rules.remove(ruleId);
    }

    public void enable(UUID ruleId) {
        Rule current = rules.get(ruleId);
        if (current == null) {
            return;
        }
        persistAndCache(new Rule(
                current.ruleId(), current.name(), true, current.priority(), current.conditions(), current.actions(), current.metadata(), current.cooldownSeconds()
        ));
    }

    public void disable(UUID ruleId) {
        Rule current = rules.get(ruleId);
        if (current == null) {
            return;
        }
        persistAndCache(new Rule(
                current.ruleId(), current.name(), false, current.priority(), current.conditions(), current.actions(), current.metadata(), current.cooldownSeconds()
        ));
    }

    /**
     * Persist-before-cache (Tier 3 Phase 7): the database write happens
     * first; only once it succeeds is the in-memory registry updated. If
     * repository.save() throws, it propagates to the caller and the cache
     * is left untouched - the two can never observably diverge as a result
     * of a failed write, since the cache write is unreachable in that case.
     */
    private void persistAndCache(Rule rule) {
        repository.save(RuleEntity.fromDomain(rule));
        rules.put(rule.ruleId(), rule);
    }

    public Optional<Rule> get(UUID ruleId) {
        return Optional.ofNullable(rules.get(ruleId));
    }

    public List<Rule> list() {
        return new ArrayList<>(rules.values());
    }

    public void clear() {
        rules.clear();
    }

    public List<Rule> listEnabledOrderedByPriority() {
        return rules.values().stream()
                .filter(Rule::enabled)
                .sorted(Comparator.comparingInt(Rule::priority))
                .toList();
    }

    private void validate(Rule rule) {
        if (rule.ruleId() == null) {
            throw new com.nexus.shared.exception.ValidationException("Rule ID cannot be null");
        }
        if (!StringUtils.hasText(rule.name())) {
            throw new com.nexus.shared.exception.ValidationException("Rule name cannot be empty");
        }
        if (rule.conditions() == null || rule.conditions().isEmpty()) {
            throw new com.nexus.shared.exception.ValidationException("Rule must have at least one condition");
        }
        if (rule.actions() == null || rule.actions().isEmpty()) {
            throw new com.nexus.shared.exception.ValidationException("Rule must have at least one action");
        }
        if (rule.cooldownSeconds() != null && rule.cooldownSeconds() <= 0) {
            throw new com.nexus.shared.exception.ValidationException("cooldownSeconds, if set, must be positive");
        }
        rule.conditions().forEach(c -> {
            if (c.field() == null) throw new com.nexus.shared.exception.ValidationException("Condition field cannot be null");
            if (c.operator() == null) throw new com.nexus.shared.exception.ValidationException("Condition operator cannot be null");
            if (c.expectedValue() == null) throw new com.nexus.shared.exception.ValidationException("Condition expected value cannot be null");
        });
        rule.actions().forEach(a -> {
            if (!StringUtils.hasText(a.actionType())) throw new com.nexus.shared.exception.ValidationException("Action type cannot be empty");
        });

        validateCapabilityAwareConditions(rule);
        validateSendCommandActions(rule);
        if (genericValidator != null) genericValidator.validate(rule);
    }

    /**
     * Phase 13 (13E): registration-time, fail-fast validation of SEND_COMMAND
     * rule actions - reuses the exact same CommandValueValidator CommandExecutor
     * uses at execution time, so the two can never disagree. This is
     * explicitly defense-in-depth alongside, not instead of, CommandExecutor's
     * own execution-time check: a device's capabilities can change after a
     * rule is created, so this registration-time pass cannot be the only
     * check.
     */
    private void validateSendCommandActions(Rule rule) {
        for (RuleAction action : rule.actions()) {
            if (!"SEND_COMMAND".equals(action.actionType())) {
                continue;
            }
            Map<String, Object> parameters = action.parameters() != null ? action.parameters() : Map.of();

            Object deviceIdRaw = parameters.get("deviceId");
            if (deviceIdRaw == null) {
                throw new com.nexus.shared.exception.ValidationException("SEND_COMMAND action requires a deviceId parameter");
            }
            UUID deviceId;
            try {
                deviceId = UUID.fromString(deviceIdRaw.toString());
            } catch (IllegalArgumentException e) {
                throw new com.nexus.shared.exception.ValidationException("SEND_COMMAND action deviceId '" + deviceIdRaw + "' is not a valid UUID");
            }
            if (!deviceRepository.existsById(deviceId)) {
                throw new com.nexus.shared.exception.ValidationException("SEND_COMMAND action targets a nonexistent device: " + deviceId);
            }

            Object capabilityRaw = parameters.get("capability");
            if (capabilityRaw == null && parameters.get("capabilityKey") != null) continue;
            CapabilityType capabilityType;
            try {
                capabilityType = CapabilityType.valueOf(String.valueOf(capabilityRaw));
            } catch (IllegalArgumentException e) {
                throw new com.nexus.shared.exception.ValidationException("SEND_COMMAND action capability '" + capabilityRaw + "' is not a known capability");
            }

            Optional<DeviceCapability> deviceCapability = deviceCapabilityRepository
                    .findByDeviceIdAndCapabilityTypeAndDirection(deviceId, capabilityType, CapabilityDirection.WRITE);
            if (deviceCapability.isEmpty()) {
                throw new com.nexus.shared.exception.ValidationException(
                        "SEND_COMMAND action targets device " + deviceId + " which has no WRITE capability " + capabilityType);
            }

            String command = parameters.get("command") != null ? parameters.get("command").toString() : null;
            Object value = parameters.get("value");
            try {
                CommandValueValidator.validate(capabilityType, deviceCapability.get(), command, value);
            } catch (CommandValueValidator.CommandValidationException e) {
                throw new com.nexus.shared.exception.ValidationException("SEND_COMMAND action value is invalid: " + e.getMessage());
            }
        }
    }

    /**
     * Phase 13. Applies ONLY to conditions with capabilityType != null -
     * every pre-Phase-13 persisted rule has capabilityType == null on every
     * condition and is therefore entirely unaffected by any check here,
     * including on update/re-save (the legacy/new distinction is
     * per-condition, not per-rule - see RuleCondition's Javadoc).
     */
    private void validateCapabilityAwareConditions(Rule rule) {
        List<RuleCondition> capabilityConditions = rule.conditions().stream()
                .filter(c -> c.capabilityType() != null)
                .toList();
        if (capabilityConditions.isEmpty()) {
            return;
        }

        List<String> sensorTypeExpectedValues = rule.conditions().stream()
                .filter(c -> c.field() == RuleField.SENSOR_TYPE)
                .map(RuleCondition::expectedValue)
                .toList();

        Optional<String> explicitDeviceId = rule.conditions().stream()
                .filter(c -> c.field() == RuleField.DEVICE_ID)
                .map(RuleCondition::expectedValue)
                .findFirst();

        for (RuleCondition condition : capabilityConditions) {
            CapabilityType capabilityType = condition.capabilityType();

            // GT/LT/GTE/LTE against a boolean-kind capability is rejected at
            // registration - fail fast rather than persist a rule that can
            // never match (Phase 13 requirement).
            if (CapabilityValueKind.of(capabilityType) == CapabilityValueKind.BOOLEAN
                    && isRangeOperator(condition.operator())) {
                throw new com.nexus.shared.exception.ValidationException(
                        "Operator " + condition.operator() + " is not valid for boolean capability " + capabilityType);
            }

            // If numeric, the expectedValue must actually parse - otherwise
            // this condition could never match anything, silently.
            if (CapabilityValueKind.of(capabilityType) == CapabilityValueKind.NUMERIC) {
                try {
                    Double.parseDouble(condition.expectedValue());
                } catch (NumberFormatException e) {
                    throw new com.nexus.shared.exception.ValidationException(
                            "expectedValue '" + condition.expectedValue() + "' is not a valid number for capability " + capabilityType);
                }
            } else {
                if (condition.expectedValue() == null
                        || !(condition.expectedValue().equalsIgnoreCase("true") || condition.expectedValue().equalsIgnoreCase("false"))) {
                    throw new com.nexus.shared.exception.ValidationException(
                            "expectedValue '" + condition.expectedValue() + "' must be 'true' or 'false' for boolean capability " + capabilityType);
                }
            }

            // Contradiction guard: a co-existing SENSOR_TYPE condition must
            // not name a different capability than this one.
            for (String sensorTypeValue : sensorTypeExpectedValues) {
                if (sensorTypeValue != null && !sensorTypeValue.equals(capabilityType.name())) {
                    throw new com.nexus.shared.exception.ValidationException(
                            "SENSOR_TYPE condition ('" + sensorTypeValue + "') contradicts capabilityType " + capabilityType);
                }
            }

            // Device-capability existence check - only possible when the
            // rule pins an explicit device; a platform-wide capability-aware
            // rule (no DEVICE_ID condition) has no device to check yet.
            if (explicitDeviceId.isPresent()) {
                UUID deviceId;
                try {
                    deviceId = UUID.fromString(explicitDeviceId.get());
                } catch (IllegalArgumentException e) {
                    throw new com.nexus.shared.exception.ValidationException(
                            "DEVICE_ID condition value '" + explicitDeviceId.get() + "' is not a valid UUID");
                }
                boolean hasCapability = deviceCapabilityRepository
                        .existsByDeviceIdAndCapabilityTypeAndDirection(deviceId, capabilityType, CapabilityDirection.READ);
                if (!hasCapability) {
                    throw new com.nexus.shared.exception.ValidationException(
                            "Device " + deviceId + " does not have a READ capability of type " + capabilityType);
                }
            }
        }
    }

    private boolean isRangeOperator(RuleOperator operator) {
        return operator == RuleOperator.GT || operator == RuleOperator.LT
                || operator == RuleOperator.GTE || operator == RuleOperator.LTE;
    }
}
