package com.nexus.device.command;

import com.nexus.action.api.dto.ActionHistoryResponse;
import com.nexus.action.application.ActionExecutionEngine;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.ActionType;
import com.nexus.device.command.api.dto.CreateDeviceCommandRequest;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.exception.DeviceNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tier 5D: the manual (non-rule) command trigger path. Reuses the existing
 * ActionExecutionEngine command path. This class constructs the request and
 * reads back its durably-persisted action; the engine's command coordinator
 * accepts ActionHistory plus desired intent before calling CommandExecutor
 * to publish. Target validation remains in CommandExecutor.prepare().
 *
 * matchId is null (ActionExecutionRequest has no rule match to reference)
 * and ActionContext(null, null) - a manually triggered command genuinely
 * has neither a triggering DomainEvent nor a matched Rule; nothing is
 * fabricated to fill either gap.
 */
@Service
public class DeviceCommandService {

    private final DeviceRepository deviceRepository;
    private final ActionExecutionEngine actionExecutionEngine;
    private final ActionHistoryRepository actionHistoryRepository;
    private com.nexus.action.outbox.CommandOutboxRepository outbox;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setOutbox(com.nexus.action.outbox.CommandOutboxRepository outbox) { this.outbox = outbox; }

    public DeviceCommandService(DeviceRepository deviceRepository,
                                 ActionExecutionEngine actionExecutionEngine,
                                 ActionHistoryRepository actionHistoryRepository) {
        this.deviceRepository = deviceRepository;
        this.actionExecutionEngine = actionExecutionEngine;
        this.actionHistoryRepository = actionHistoryRepository;
    }

    public ActionHistoryResponse dispatchCommand(UUID deviceId, CreateDeviceCommandRequest request) {
        if (!deviceRepository.existsById(deviceId)) {
            throw new DeviceNotFoundException(deviceId);
        }

        if (request.capability() == null && (request.capabilityKey() == null || request.capabilityKey().isBlank())) {
            throw new IllegalArgumentException("capability or capabilityKey is required");
        }
        UUID requestId = UUID.randomUUID();
        Map<String,Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("deviceId", deviceId.toString());
        if(request.capability()!=null) parameters.put("capability",request.capability().name());
        if(request.capabilityKey()!=null) parameters.put("capabilityKey",request.capabilityKey());
        parameters.put("command",request.command()); parameters.put("value",request.value());
        ActionExecutionRequest actionRequest = new ActionExecutionRequest(
                requestId,
                null, // no RuleMatch - this is a manually triggered command
                ActionType.SEND_COMMAND,
                parameters
        );

        // SEND_COMMAND acceptance is committed before publish; its publish
        // result is updated before process() returns. Non-command actions
        // still use the engine's synchronous ActionHistoryPersister listener.
        actionExecutionEngine.process(List.of(actionRequest), new ActionContext(null, null), requestId.toString());

        ActionHistoryRecord record = actionHistoryRepository.findById(requestId)
                .orElseThrow(() -> new IllegalStateException(
                        "Action history record missing immediately after synchronous processing: " + requestId));
        // Additive (Phase 4): a command accepted through the outbox is QUEUED/IN_FLIGHT/PUBLISHED/DEAD_LETTER; a queued
        // command reports ACCEPTED_FOR_DISPATCH with that delivery state, never SUCCESS.
        var row = outbox == null ? java.util.Optional.<com.nexus.action.outbox.CommandOutboxEntity>empty() : outbox.findById(requestId);
        return ActionHistoryResponse.from(record).withDelivery(row.map(o -> o.getState().name()).orElse(null),
                row.map(o -> o.getTerminalReason() == null ? null : o.getTerminalReason().name()).orElse(null));
    }
}
