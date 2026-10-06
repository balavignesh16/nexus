package com.nexus.action.application;

import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.action.domain.*;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.twin.domain.DesiredTwinState;
import com.nexus.twin.domain.DesiredTwinStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.Instant;

/** The device row lock serializes durable acceptance, including first intent for a target. */
@Service
public class CommandAcceptanceService {
    private final DeviceRepository devices;
    private final CommandExecutor executor;
    private final ActionHistoryRepository actions;
    private final DesiredTwinStateRepository desired;

    public CommandAcceptanceService(DeviceRepository devices, CommandExecutor executor,
            ActionHistoryRepository actions, DesiredTwinStateRepository desired) {
        this.devices=devices; this.executor=executor; this.actions=actions; this.desired=desired;
    }

    // Rule actions can run inside the telemetry transaction. A separate commit
    // is required before the caller is permitted to publish MQTT.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommandExecutor.PreparedCommand accept(ActionExecutionRequest request, ActionContext context,
            Instant requestedAt) {
        Object rawDevice = request.parameters() == null ? null : request.parameters().get("deviceId");
        java.util.UUID deviceId;
        try { deviceId = java.util.UUID.fromString(String.valueOf(rawDevice)); }
        catch (RuntimeException e) { throw new CommandExecutor.CommandRejectedException("Missing or invalid deviceId parameter"); }
        devices.findByIdForUpdate(deviceId).orElseThrow(() ->
                new CommandExecutor.CommandRejectedException("Device not found: " + deviceId));
        CommandExecutor.PreparedCommand target = executor.prepare(request);
        Instant acceptedAt = Instant.now();
        String source = context != null && context.matchedRule() != null ? "RULE_ACTION" : "MANUAL_COMMAND";
        actions.saveAndFlush(new ActionHistoryRecord(request.requestId(), request.matchId(), deviceId,
                ActionType.SEND_COMMAND, ExecutionStatus.ACCEPTED_FOR_DISPATCH, request.parameters(),
                acceptedAt, "Durably accepted; MQTT delivery not yet known"));
        desired.saveAndFlush(new DesiredTwinState(deviceId, target.targetKey(), target.bindingId(),
                target.legacyCapabilityType(), target.subtargetKey(), target.capabilityDefinitionId(),
                target.capabilityKey(), target.valueType(), target.value(), target.unit(), source,
                request.requestId(), requestedAt, acceptedAt));
        return target;
    }
}
