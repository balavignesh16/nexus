package com.nexus.twin.application;

import com.nexus.device.binding.DeviceCapabilityBindingRepository;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.telemetry.domain.TelemetryRecord;
import com.nexus.twin.domain.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Device-row serialization is shared with command acceptance and reported-state writes. */
@Service
public class VerificationService {
    private final CurrentTwinVerificationRepository current;
    private final DeviceReadbackMappingRepository mappings;
    private final DesiredTwinStateRepository desired;
    private final DeviceCapabilityBindingRepository bindings;
    private final DeviceRepository devices;
    private final TwinStateTransitionRepository transitions;
    private final TwinStateQualityEvaluator quality;
    private final ReadbackComparison comparison;
    private final Clock clock;
    private com.nexus.rule.trigger.RuleSourceFacts ruleFacts;
    @org.springframework.beans.factory.annotation.Autowired
    void setRuleFacts(com.nexus.rule.trigger.RuleSourceFacts ruleFacts){this.ruleFacts=ruleFacts;}
    public VerificationService(CurrentTwinVerificationRepository current,DeviceReadbackMappingRepository mappings,
            DesiredTwinStateRepository desired,DeviceCapabilityBindingRepository bindings,DeviceRepository devices,
            TwinStateTransitionRepository transitions,TwinStateQualityEvaluator quality,ReadbackComparison comparison,Clock clock) {
        this.current=current;this.mappings=mappings;this.desired=desired;this.bindings=bindings;
        this.devices=devices;this.transitions=transitions;this.quality=quality;this.comparison=comparison;this.clock=clock;
    }

    /** Called inside the already device-locked desired acceptance transaction. */
    public void accepted(DesiredTwinState intent,String correlationId) {
        var id=id(intent);
        var old=current.findById(id).orElse(null);
        VerificationStatus previousStatus=old==null?null:old.getVerificationStatus();
        var mapping=usable(intent);
        Instant now=clock.instant();
        VerificationStatus status=mapping.isPresent()?VerificationStatus.PENDING:VerificationStatus.UNVERIFIED;
        Instant deadline=mapping.map(m->intent.getAcceptedForDispatchAt().plusSeconds(m.getVerificationTimeoutSeconds())).orElse(null);
        UUID mappingId=mapping.map(DeviceReadbackMapping::getId).orElse(null);
        String reason=mapping.isPresent()?null:"READBACK_MAPPING_UNAVAILABLE";
        var next=old==null?new CurrentTwinVerificationState(intent.getDeviceId(),intent.getTargetKey(),intent.getSubtargetKey(),
                intent.getCommandId(),status,now,deadline,mappingId,reason):old;
        if(old!=null)next.rearm(intent.getCommandId(),status,now,deadline,mappingId,reason);
        current.saveAndFlush(next);
        if(previousStatus!=next.getVerificationStatus())
            saveConvergence(TwinStateTransition.convergence(intent,previousStatus,next,now,correlationId));
    }

    /** Called after accepted reported mutation, in that same telemetry transaction. */
    public void observed(TelemetryRecord telemetry,DigitalTwin reported,UUID echo,String correlationId,Instant receivedAt) {
        if(reported==null||telemetry.getBindingId()==null||echo==null)return;
        if(quality.evaluate(reported,receivedAt).state()!=TwinStateQuality.GOOD)return;
        for(var mapping:mappings.findByDeviceIdAndReadBindingIdAndEnabledTrue(telemetry.getDeviceId(),telemetry.getBindingId())) {
            if(!"COMMAND_ECHO".equals(mapping.getQualificationMode()))continue;
            var id=new DesiredTwinStateId(telemetry.getDeviceId(),"binding:"+mapping.getWriteBindingId(),mapping.getWriteSubtargetKey());
            var intent=desired.findById(id).orElse(null);
            if(intent==null||!echo.equals(intent.getCommandId())||!receivedAt.isAfter(intent.getAcceptedForDispatchAt())
                    ||usable(intent).filter(m->m.getId().equals(mapping.getId())).isEmpty())continue;
            var state=current.findById(new VerificationStateId(telemetry.getDeviceId(),
                    "binding:"+mapping.getWriteBindingId(),mapping.getWriteSubtargetKey())).orElse(null);
            if(state==null||!state.getDesiredCommandId().equals(intent.getCommandId())||state.getVerificationStatus()==VerificationStatus.UNVERIFIED)continue;
            Object selected=comparison.select(mapping.getSelectorKey(),mapping.getSelectorPath(),reported.typedValue());
            Object expected=intent.typedValue();
            if(!intent.getSubtargetKey().isEmpty()&&expected instanceof Map<?,?> command)expected=command.get("state");
            if(selected==null||expected==null)continue;
            boolean match=comparison.matches(mapping.getComparatorKey(),
                    intent.getSubtargetKey().isEmpty()?intent.getValueType():com.nexus.device.capability.definition.CapabilityValueType.BOOLEAN,
                    expected,selected,mapping.getNumericTolerance());
            VerificationStatus before=state.getVerificationStatus();
            state.observe(telemetry.getId(),reported.stateKey(),reported.bindingId(),reported.latestTimestamp(),
                    receivedAt,correlationId,selected,mapping.getComparatorKey(),mapping.getNumericTolerance(),match);
            if(match)state.change(VerificationStatus.CONVERGED,clock.instant(),null);
            else if(before!=VerificationStatus.PENDING||state.getVerificationDeadlineAt()!=null
                    && !clock.instant().isBefore(state.getVerificationDeadlineAt()))
                state.change(VerificationStatus.DIVERGED,clock.instant(),"QUALIFIED_READBACK_MISMATCH");
            current.saveAndFlush(state);
            if(before!=state.getVerificationStatus())
                saveConvergence(TwinStateTransition.convergence(intent,before,state,clock.instant(),correlationId));
        }
    }

    @Transactional
    public void deadline(VerificationStateId candidate) {
        devices.findByIdForUpdate(candidate.deviceId()).orElseThrow();
        var state=current.findById(candidate).orElse(null);
        var intent=desired.findById(new DesiredTwinStateId(candidate.deviceId(),candidate.targetKey(),candidate.subtargetKey())).orElse(null);
        if(state==null||intent==null||!state.getDesiredCommandId().equals(intent.getCommandId())
                ||state.getVerificationStatus()!=VerificationStatus.PENDING||state.getVerificationDeadlineAt()==null
                ||clock.instant().isBefore(state.getVerificationDeadlineAt()))return;
        if(usable(intent).filter(m->m.getId().equals(state.getMappingId())).isEmpty()) {
            change(intent,state,VerificationStatus.UNVERIFIED,"READBACK_MAPPING_UNAVAILABLE");return;
        }
        boolean mismatched="MISMATCH".equals(state.getComparisonResult());
        change(intent,state,mismatched?VerificationStatus.DIVERGED:VerificationStatus.TIMED_OUT,
                mismatched?"QUALIFIED_READBACK_MISMATCH":"NO_QUALIFIED_READBACK");
    }

    public List<VerificationStateId> expired(int limit) {
        return current.findExpired(clock.instant(),PageRequest.of(0,limit)).stream()
                .map(v->new VerificationStateId(v.getDeviceId(),v.getTargetKey(),v.getSubtargetKey())).toList();
    }

    public void mappingAdded(UUID deviceId,UUID writeId,String subtarget) {
        var id=new VerificationStateId(deviceId,"binding:"+writeId,subtarget);
        current.findById(id).ifPresent(state->{if(state.getVerificationStatus()==VerificationStatus.UNVERIFIED){
            state.change(VerificationStatus.UNVERIFIED,clock.instant(),"MAPPING_ADDED_AFTER_INTENT");current.save(state);}});
    }

    public void mappingUnavailable(UUID deviceId,UUID writeId,String subtarget) {
        var id=new VerificationStateId(deviceId,"binding:"+writeId,subtarget);
        var state=current.findById(id).orElse(null);
        var intent=desired.findById(new DesiredTwinStateId(deviceId,id.targetKey(),subtarget)).orElse(null);
        if(state!=null&&intent!=null&&state.getDesiredCommandId().equals(intent.getCommandId())
                &&usable(intent).filter(mapping->mapping.getId().equals(state.getMappingId())).isEmpty())
            change(intent,state,VerificationStatus.UNVERIFIED,"READBACK_MAPPING_UNAVAILABLE");
    }

    @Transactional
    public void commandFailed(UUID deviceId,UUID commandId,String reason) {
        devices.findByIdForUpdate(deviceId).orElseThrow();
        for(var intent:desired.findByDeviceId(deviceId))if(intent.getCommandId().equals(commandId)) {
            var state=current.findById(id(intent)).orElse(null);
            if(state!=null&&state.getDesiredCommandId().equals(commandId)
                    &&state.getVerificationStatus()==VerificationStatus.PENDING)
                change(intent,state,VerificationStatus.UNVERIFIED,reason);
        }
    }

    /** Domain-only clear; no public endpoint is introduced by P2.4. */
    @Transactional
    public void clearDesired(UUID deviceId,String targetKey,String subtargetKey,String correlationId) {
        devices.findByIdForUpdate(deviceId).orElseThrow();
        var desiredId=new DesiredTwinStateId(deviceId,targetKey,subtargetKey);
        var intent=desired.findById(desiredId).orElse(null);
        if(intent==null)return;
        var verificationId=new VerificationStateId(deviceId,targetKey,subtargetKey);
        var state=current.findById(verificationId).orElse(null);
        Instant now=clock.instant();
        if(state!=null){
            transitions.save(TwinStateTransition.convergenceCleared(intent,state,now,correlationId));
            current.delete(state);
        }
        transitions.save(TwinStateTransition.desiredCleared(intent,now,correlationId));
        desired.delete(intent);
    }

    private void change(DesiredTwinState intent,CurrentTwinVerificationState state,VerificationStatus status,String reason) {
        VerificationStatus previous=state.getVerificationStatus();
        state.change(status,clock.instant(),reason);current.saveAndFlush(state);
        if(previous!=status)saveConvergence(TwinStateTransition.convergence(intent,previous,state,clock.instant(),null));
    }

    private Optional<DeviceReadbackMapping> usable(DesiredTwinState intent) {
        if(intent.getBindingId()==null)return Optional.empty();
        return mappings.findByDeviceIdAndWriteBindingIdAndWriteSubtargetKeyAndEnabledTrue(intent.getDeviceId(),
                intent.getBindingId(),intent.getSubtargetKey()).filter(m->{
            if(!"COMMAND_ECHO".equals(m.getQualificationMode()))return false;
            var write=bindings.findById(m.getWriteBindingId()).orElse(null);
            var read=bindings.findById(m.getReadBindingId()).orElse(null);
            return write!=null&&read!=null&&write.isEnabled()&&read.isEnabled()
                    &&write.getDeviceId().equals(intent.getDeviceId())&&read.getDeviceId().equals(intent.getDeviceId())
                    &&Objects.equals(write.getUnit(),read.getUnit())
                    &&comparison.supports(m.getSelectorKey(),m.getSelectorPath(),m.getComparatorKey(),
                        intent.getSubtargetKey().isEmpty()?intent.getValueType():com.nexus.device.capability.definition.CapabilityValueType.BOOLEAN,
                        read.getDefinition().getValueType());
        });
    }
    /** A committed CONVERGENCE_CHANGED transition is the durable source of VERIFICATION_CHANGED facts. */
    private void saveConvergence(TwinStateTransition transition) {
        var saved=transitions.save(transition);
        if(ruleFacts!=null && saved!=null) ruleFacts.verificationChanged(saved);
    }
    private static VerificationStateId id(DesiredTwinState intent){return new VerificationStateId(intent.getDeviceId(),intent.getTargetKey(),intent.getSubtargetKey());}
}
