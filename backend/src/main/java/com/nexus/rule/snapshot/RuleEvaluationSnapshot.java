package com.nexus.rule.snapshot;

import com.nexus.rule.temporal.TemporalRuntimeEvidence;
import com.nexus.twin.application.TwinStateQualityEvaluator;
import com.nexus.twin.domain.DigitalTwin;
import java.time.Instant;
import java.util.*;

/**
 * Everything one event batch may read, acquired in a single REPEATABLE READ transaction.
 * Evaluation never touches the registry, caches or repositories again. {@code triggerSuperseded} is the verdict of comparing the
 * trigger fact's source identity with the state captured here: when present, the fact's source was already replaced by a newer one
 * and nothing may be evaluated or decided for it. {@code hysteresis} holds the current-generation latch of every hysteresis condition
 * of the captured rules, read in the same snapshot.
 */
public record RuleEvaluationSnapshot(UUID snapshotId,Instant acquiredAt,Instant evaluationTime,String isolation,
        List<CapturedRule> rules,Map<UUID,List<DigitalTwin>> twins,Map<UUID,CapturedConnectivity> connectivity,
        Map<String,CapturedCapability> capabilities,Set<String> unresolvedCapabilities,
        Map<String,TwinStateQualityEvaluator.Inputs> qualityInputs,Map<String,TemporalRuntimeEvidence> temporal,
        Optional<String> triggerSuperseded,Map<String,Boolean> hysteresis) {
    public RuleEvaluationSnapshot {
        rules=List.copyOf(rules);twins=Map.copyOf(twins);connectivity=Map.copyOf(connectivity);
        capabilities=Map.copyOf(capabilities);unresolvedCapabilities=Set.copyOf(unresolvedCapabilities);qualityInputs=Map.copyOf(qualityInputs);
        temporal=Map.copyOf(temporal);
        triggerSuperseded=triggerSuperseded==null?Optional.empty():triggerSuperseded;
        hysteresis=hysteresis==null?Map.of():Map.copyOf(hysteresis);
    }
    public static String capabilityKey(UUID device,String identifier,String direction){
        return device+"|"+direction+"|"+(identifier==null?"":identifier.trim().toLowerCase(Locale.ROOT));
    }
    public static String hysteresisKey(UUID rule,String conditionId,UUID scope){return rule+"|"+conditionId+"|"+scope;}
    public static String twinKey(DigitalTwin twin){return twin.deviceId()+"|"+twin.stateKey();}
    public static String temporalKey(UUID versionId,String conditionId,UUID scope){return versionId+"|"+conditionId+"|"+scope;}
}
