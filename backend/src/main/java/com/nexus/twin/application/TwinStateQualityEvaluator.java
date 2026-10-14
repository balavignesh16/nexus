package com.nexus.twin.application;

import com.nexus.device.binding.DeviceCapabilityBindingRepository;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.capability.definition.CapabilityDefinitionRepository;
import com.nexus.device.capability.definition.validation.CapabilityOperation;
import com.nexus.device.capability.definition.validation.CapabilityValueValidator;
import com.nexus.twin.domain.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class TwinStateQualityEvaluator {
    private static final Logger log=LoggerFactory.getLogger(TwinStateQualityEvaluator.class);
    private final DeviceConnectivityRegistry connectivity;
    private final DeviceCapabilityBindingRepository bindings;
    private final FreshnessPolicy freshness;
    private final Clock clock;
    private final CapabilityDefinitionRepository definitions;
    private final CapabilityValueValidator valueValidator;
    public TwinStateQualityEvaluator(DeviceConnectivityRegistry connectivity,DeviceCapabilityBindingRepository bindings,
            FreshnessPolicy freshness,Clock clock){this(connectivity,bindings,freshness,clock,null,null);}

    @Autowired
    public TwinStateQualityEvaluator(DeviceConnectivityRegistry connectivity,DeviceCapabilityBindingRepository bindings,
            FreshnessPolicy freshness,Clock clock,CapabilityDefinitionRepository definitions,
            CapabilityValueValidator valueValidator){this.connectivity=connectivity;this.bindings=bindings;
        this.freshness=freshness;this.clock=clock;this.definitions=definitions;this.valueValidator=valueValidator;}

    public TwinStateQualityEvidence evaluate(DigitalTwin twin){return evaluate(twin,clock.instant());}
    public TwinStateQualityEvidence evaluateMissing(java.util.UUID deviceId,java.util.UUID bindingId){
        Instant evaluatedAt=clock.instant();
        int threshold=threshold(bindingId);
        String connectivityName=connectivity.get(deviceId).map(c->c.status().name()).orElse("UNKNOWN");
        return new TwinStateQualityEvidence(TwinStateQuality.UNKNOWN,"NO_REPORTED_OBSERVATION",evaluatedAt,
                null,threshold,connectivityName);
    }
    /** Inputs a quality decision depends on besides the twin row; captured together by a frozen snapshot. */
    public record Inputs(String connectivity,int thresholdSeconds,boolean persistedValueValid){}

    public TwinStateQualityEvidence evaluate(DigitalTwin twin,Instant evaluatedAt){
        String connectivityName=connectivity.get(twin.deviceId()).map(c->c.status().name()).orElse("UNKNOWN");
        return evaluate(twin,evaluatedAt,liveInputs(twin,connectivityName));
    }
    /** Reads contract inputs through the current transaction; connectivity is supplied by the caller. */
    public Inputs liveInputs(DigitalTwin twin,String connectivityName){
        boolean valid=twin.typedValue()==null||twin.receivedAt()==null||isPersistedValueValid(twin);
        return new Inputs(connectivityName,threshold(twin.bindingId()),valid);
    }
    /** Pure decision over captured inputs: one evaluation time, no further reads. */
    public TwinStateQualityEvidence evaluate(DigitalTwin twin,Instant evaluatedAt,Inputs inputs){
        int threshold=inputs.thresholdSeconds();String connectivityName=inputs.connectivity();
        if(twin.typedValue()==null)return evidence(TwinStateQuality.UNKNOWN,"MISSING_OR_INVALID_VALUE",evaluatedAt,twin,threshold,connectivityName);
        if(twin.receivedAt()==null)return evidence(TwinStateQuality.UNKNOWN,"RECEIPT_TIME_UNAVAILABLE",evaluatedAt,twin,threshold,connectivityName);
        if(!inputs.persistedValueValid())return evidence(TwinStateQuality.UNKNOWN,"INVALID_PERSISTED_VALUE",evaluatedAt,twin,threshold,connectivityName);
        if("UNKNOWN".equals(connectivityName))return evidence(TwinStateQuality.UNKNOWN,"CONNECTIVITY_UNOBSERVED",evaluatedAt,twin,threshold,connectivityName);
        if(DeviceConnectivityStatus.OFFLINE.name().equals(connectivityName))return evidence(TwinStateQuality.OFFLINE,"DEVICE_OFFLINE",evaluatedAt,twin,threshold,connectivityName);
        long age=Math.max(0,Duration.between(twin.receivedAt(),evaluatedAt).getSeconds());
        return age<=threshold?evidence(TwinStateQuality.GOOD,"WITHIN_FRESHNESS_THRESHOLD",evaluatedAt,twin,threshold,connectivityName)
                :evidence(TwinStateQuality.STALE,"FRESHNESS_THRESHOLD_EXCEEDED",evaluatedAt,twin,threshold,connectivityName);
    }
    private boolean isPersistedValueValid(DigitalTwin twin){
        if(twin.capabilityDefinitionId()==null||definitions==null||valueValidator==null)return true;
        var definition=definitions.findById(twin.capabilityDefinitionId());
        if(definition.isEmpty()){
            log.warn("Twin state references missing capability definition device={} stateKey={} definitionId={}",
                    twin.deviceId(),twin.stateKey(),twin.capabilityDefinitionId());
            return false;
        }
        boolean valid=valueValidator.validate(definition.get(),twin.typedValue(),twin.latestUnit(),
                CapabilityOperation.TELEMETRY,null).isValid();
        if(!valid)log.warn("Twin state contains invalid persisted value device={} stateKey={}",twin.deviceId(),twin.stateKey());
        return valid;
    }
    private int threshold(java.util.UUID bindingId){
        if(bindingId==null)return freshness.systemDefaultSeconds();
        return bindings.findById(bindingId).map(b->freshness.effective(b.getFreshnessThresholdSeconds(),
                b.getTemplateCapability()==null?null:b.getTemplateCapability().getFreshnessThresholdSeconds()))
                .orElse(freshness.systemDefaultSeconds());
    }
    private static TwinStateQualityEvidence evidence(TwinStateQuality quality,String reason,Instant evaluatedAt,
            DigitalTwin twin,int threshold,String connectivity){return new TwinStateQualityEvidence(quality,reason,evaluatedAt,
                    twin.receivedAt(),threshold,connectivity);}
}
