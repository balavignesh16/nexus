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
 * ActionExecutionEngine/CommandExecutor/ActionHistoryRecord pipeline
 * entirely - this class only constructs the request and reads back the
 * durably-persisted result; it duplicates none of the authorization,
 * connectivity, or MQTT-publish logic that already lives in CommandExecutor.
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

        UUID requestId = UUID.randomUUID();
        ActionExecutionRequest actionRequest = new ActionExecutionRequest(
                requestId,
                null, // no RuleMatch - this is a manually triggered command
                ActionType.SEND_COMMAND,
                Map.of(
                        "deviceId", deviceId.toString(),
                        "capability", request.capability().name(),
                        "command", request.command(),
                        "value", request.value()
                )
        );

        // ActionExecutionEngine.process()'s internal event publication is
        // synchronous (Spring's default ApplicationEventMulticaster, no
        // @Async on ActionHistoryPersister) - by the time process() returns,
        // the ActionHistoryRecord is already durably persisted. This is the
        // same guarantee ActionExecutionEngineTest's
        // shouldPersistActionHistoryBeforeBroadcastingRealtimeMessage already
        // relies on and proves.
        actionExecutionEngine.process(List.of(actionRequest), new ActionContext(null, null), requestId.toString());

        ActionHistoryRecord record = actionHistoryRepository.findById(requestId)
                .orElseThrow(() -> new IllegalStateException(
                        "Action history record missing immediately after synchronous processing: " + requestId));
        return ActionHistoryResponse.from(record);
    }
}
