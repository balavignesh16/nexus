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
import java.time.Clock;
import com.nexus.twin.domain.DesiredTwinStateId;
import com.nexus.twin.domain.TwinStateTransition;
import com.nexus.twin.domain.TwinStateTransitionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import com.nexus.twin.application.VerificationService;

/** The device row lock serializes durable acceptance, including first intent for a target. */
@Service
public class CommandAcceptanceService {
    private com.nexus.rule.application.RuleExecutionAuthorization ruleAuthorization;
    private org.springframework.jdbc.core.JdbcTemplate contractLocks;
    private jakarta.persistence.EntityManager entities;
    private com.nexus.rule.application.RuleCapabilityValidator ruleValidator;
    @Autowired
    void setContractBoundary(org.springframework.jdbc.core.JdbcTemplate locks,jakarta.persistence.EntityManager entities,com.nexus.rule.application.RuleCapabilityValidator validator){this.contractLocks=locks;this.entities=entities;this.ruleValidator=validator;}
    @Autowired
    void setRuleAuthorization(com.nexus.rule.application.RuleExecutionAuthorization authorization){this.ruleAuthorization=authorization;}
    private final DeviceRepository devices;
    private final CommandExecutor executor;
    private final ActionHistoryRepository actions;
    private final DesiredTwinStateRepository desired;
    private final TwinStateTransitionRepository transitions;
    private final Clock clock;
    private VerificationService verification;

    public CommandAcceptanceService(DeviceRepository devices, CommandExecutor executor,
            ActionHistoryRepository actions, DesiredTwinStateRepository desired) {
        this(devices,executor,actions,desired,null,Clock.systemUTC());
    }

    @Autowired
    public CommandAcceptanceService(DeviceRepository devices, CommandExecutor executor,
            ActionHistoryRepository actions, DesiredTwinStateRepository desired,
            TwinStateTransitionRepository transitions,Clock clock) {
        this.devices=devices; this.executor=executor; this.actions=actions; this.desired=desired;
        this.transitions=transitions; this.clock=clock;
    }

    @Autowired
    void setVerification(VerificationService verification){this.verification=verification;}

    // Phase 4 (P4.2): present only as optional collaborators; with the outbox flag OFF (default) none of them is used and
    // acceptance is exactly the pre-Phase-4 behaviour.
    private com.nexus.action.outbox.CommandOutboxSettings outboxSettings;
    private com.nexus.action.outbox.CommandOutboxRepository outbox;
    private com.nexus.action.outbox.CommandStateHistory stateHistory;
    private com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired(required = false)
    void setOutbox(com.nexus.action.outbox.CommandOutboxSettings settings, com.nexus.action.outbox.CommandOutboxRepository outbox,
            com.nexus.action.outbox.CommandStateHistory stateHistory, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.outboxSettings=settings; this.outbox=outbox; this.stateHistory=stateHistory; this.mapper=mapper;
    }

    // Rule actions can run inside the telemetry transaction. A separate commit
    // is required before the caller is permitted to publish MQTT.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommandExecutor.PreparedCommand accept(ActionExecutionRequest request, ActionContext context,
            Instant requestedAt) {
        return accept(request,context,requestedAt,request.requestId().toString());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommandExecutor.PreparedCommand accept(ActionExecutionRequest request, ActionContext context,
            Instant requestedAt,String correlationId) {
        boolean ruleOrigin=context!=null&&context.matchedRule()!=null;
        Object rawDevice = request.parameters() == null ? null : request.parameters().get("deviceId");
        java.util.UUID deviceId;
        try { deviceId = java.util.UUID.fromString(String.valueOf(rawDevice)); }
        catch (RuntimeException e) { if(ruleOrigin)throw new com.nexus.rule.application.RuleExecutionSuppressedException("INVALID_TARGET_DEVICE");throw new CommandExecutor.CommandRejectedException("Missing or invalid deviceId parameter"); }
        devices.findByIdForUpdate(deviceId).orElseThrow(() ->
                ruleOrigin?new com.nexus.rule.application.RuleExecutionSuppressedException("TARGET_DEVICE_UNAVAILABLE"):new CommandExecutor.CommandRejectedException("Device not found: " + deviceId));
        if(contractLocks!=null){contractLocks.queryForList("SELECT id FROM capability_definitions ORDER BY id FOR SHARE");entities.clear();}
        CommandExecutor.PreparedCommand target;
        try {
            if(ruleOrigin&&ruleValidator!=null)ruleValidator.validate(new com.nexus.rule.domain.Rule(context.matchedRule().ruleId(),"Action safety",false,0,
                java.util.List.of(new com.nexus.rule.domain.RuleCondition(com.nexus.rule.domain.RuleField.EVENT_TYPE,com.nexus.rule.domain.RuleOperator.EQ,"TWIN_UPDATED")),
                java.util.List.of(new com.nexus.rule.domain.RuleAction("SEND_COMMAND",request.parameters())),java.util.Map.of()));
            target = executor.prepare(request);
        }catch(RuntimeException invalid){
            if(ruleOrigin)throw new com.nexus.rule.application.RuleExecutionSuppressedException("INVALID_ACTION_TARGET: "+invalid.getMessage());
            throw invalid;
        }
        if(ruleAuthorization!=null)ruleAuthorization.check(request,context);
        Instant acceptedAt = clock.instant();
        String source = context != null && context.matchedRule() != null ? "RULE_ACTION" : "MANUAL_COMMAND";
        actions.saveAndFlush(new ActionHistoryRecord(request.requestId(), request.matchId(), deviceId,
                ActionType.SEND_COMMAND, ExecutionStatus.ACCEPTED_FOR_DISPATCH, request.parameters(),
                acceptedAt, "Durably accepted; MQTT delivery not yet known"));
        DesiredTwinState previous=desired.findById(new DesiredTwinStateId(deviceId,target.targetKey(),target.subtargetKey())).orElse(null);
        DesiredTwinState next=new DesiredTwinState(deviceId, target.targetKey(), target.bindingId(),
                target.legacyCapabilityType(), target.subtargetKey(), target.capabilityDefinitionId(),
                target.capabilityKey(), target.valueType(), target.value(), target.unit(), source,
                request.requestId(), requestedAt, acceptedAt);
        desired.saveAndFlush(next);
        if(transitions!=null)transitions.save(TwinStateTransition.desired(previous,next,clock.instant(),correlationId));
        if(verification!=null)verification.accepted(next,correlationId);
        if(outboxSettings!=null&&outboxSettings.enabled())enqueue(request,target,deviceId,acceptedAt,correlationId,ruleOrigin);
        return target;
    }

    /**
     * Outbox mode (contract R07/R08): the delivery obligation is written in THIS acceptance transaction, after ActionHistory,
     * desired state, desired transition and verification, so either everything commits or nothing does. The payload is built
     * once here, with the acceptance-time epoch-second timestamp, and is never rebuilt or refreshed. A payload that is not a
     * complete wire command is refused here, before any row is committed.
     */
    private void enqueue(ActionExecutionRequest request,CommandExecutor.PreparedCommand target,java.util.UUID deviceId,
            Instant acceptedAt,String correlationId,boolean ruleOrigin) {
        // DATABASE time is the single authority for the whole delivery lifecycle in outbox mode: the payload timestamp (still an
        // integer of epoch seconds, exactly what the firmware compares with its own NTP clock), the creation and scheduling
        // timestamps and the expiry all come from the database clock at acceptance, so an application clock that is wrong in
        // either direction cannot make a command unclaimable, immortal or stale. (With the flag OFF the legacy publish-time
        // application clock is unchanged.)
        Instant databaseNow=contractLocks.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();
        com.nexus.action.outbox.OutboxPayload payload;
        try {
            payload=com.nexus.action.outbox.OutboxPayload.create(request.requestId(),target.wireCapability(),target.command(),
                    target.value(),databaseNow.getEpochSecond(),mapper);
            com.nexus.action.outbox.OutboxPayloadValidator.validate(request.requestId(),payload,mapper);
        } catch (IllegalArgumentException invalid) {
            if(ruleOrigin)throw new com.nexus.rule.application.RuleExecutionSuppressedException("INVALID_ACTION_TARGET: "+invalid.getMessage());
            throw new CommandExecutor.CommandRejectedException(invalid.getMessage());
        }
        // Per-device order key: strictly later than every earlier row of this device (the device row lock serialises
        // acceptances), so created_at order is the acceptance order even when two commands fall in the same microsecond.
        Instant createdAt=databaseNow.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        java.sql.Timestamp latest=contractLocks==null?null:contractLocks.queryForObject(
                "SELECT max(created_at) FROM command_outbox WHERE device_id = ?",java.sql.Timestamp.class,deviceId);
        if(latest!=null&&!latest.toInstant().isBefore(createdAt))createdAt=latest.toInstant().plus(1,java.time.temporal.ChronoUnit.MICROS);
        try {
            outbox.saveAndFlush(com.nexus.action.outbox.CommandOutboxEntity.queued(request.requestId(),deviceId,payload,
                    outboxSettings.deliveryWindow(),outboxSettings.maxAttempts(),correlationId,createdAt));
        } catch (org.springframework.dao.DataIntegrityViolationException refused) {
            // A constraint refused the delivery obligation: the whole acceptance fails (this transaction rolls back) and the
            // caller is told the command was rejected. Nothing is published and no desired intent is stored.
            if(ruleOrigin)throw new com.nexus.rule.application.RuleExecutionSuppressedException("INVALID_ACTION_TARGET: delivery obligation refused");
            throw new CommandExecutor.CommandRejectedException("Command could not be queued for delivery");
        }
        stateHistory.append(request.requestId(),com.nexus.action.outbox.CommandStateHistory.Fact.ACCEPTED,null,null,0,"ACCEPTED",
                com.nexus.action.outbox.CommandStateHistory.Source.ACCEPTANCE,correlationId,createdAt,null);
        stateHistory.record(new com.nexus.action.outbox.OutboxTransitionRecord(request.requestId(),null,
                com.nexus.action.outbox.OutboxState.QUEUED,0,"QUEUED",correlationId,createdAt));
    }
}
