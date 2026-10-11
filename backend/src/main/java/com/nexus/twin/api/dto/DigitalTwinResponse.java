package com.nexus.twin.api.dto;

import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.DesiredTwinState;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.CommandStatus;
import com.nexus.twin.domain.TwinStateQualityEvidence;
import com.nexus.twin.domain.CurrentTwinVerificationState;
import com.nexus.twin.domain.DeviceReadbackMapping;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public record DigitalTwinResponse(
    UUID deviceId,
    Instant latestTimestamp,
    String latestSensorType,
    Double latestValue,
    String latestUnit,
    Instant lastSeen,
    String quality,
    Map<String, Object> metadata,
    Map<String, CapabilityState> reportedState,
    Map<String, DesiredState> desiredState,
    Map<String, EffectiveState> effectiveState,
    Map<String, VerificationState> verification
) {
    public DigitalTwinResponse(UUID deviceId,Instant latestTimestamp,String latestSensorType,Double latestValue,
            String latestUnit,Instant lastSeen,String quality,Map<String,Object> metadata,
            Map<String,CapabilityState> reportedState,Map<String,DesiredState> desiredState,
            Map<String,EffectiveState> effectiveState) {
        this(deviceId,latestTimestamp,latestSensorType,latestValue,latestUnit,lastSeen,quality,metadata,
                reportedState,desiredState,effectiveState,Map.of());
    }
    public record VerificationState(String status,Instant updatedAt,Instant deadlineAt,UUID desiredCommandId,
            UUID mappingId,UUID readbackBindingId,String readbackStateKey,Object readbackValue,
            Instant readbackEventTime,Instant readbackReceivedAt,TwinStateQualityEvidence readbackStateQuality,
            String readbackEvidence,String attribution,String comparator,java.math.BigDecimal tolerance,
            String comparisonResult,String discrepancyReason,UUID readbackTelemetryId) {
        public static VerificationState from(CurrentTwinVerificationState state,
                TwinStateQualityEvidence quality) {
            return new VerificationState(state.getVerificationStatus().name(),state.getVerificationUpdatedAt(),
                    state.getVerificationDeadlineAt(),state.getDesiredCommandId(),state.getMappingId(),
                    state.getLastReadbackBindingId(),state.getLastReadbackStateKey(),state.getReadbackValue(),
                    state.getLastReadbackEventTime(),state.getLastReadbackReceivedAt(),quality,
                    state.getReadbackEvidence(),state.getReadbackAttribution(),state.getComparatorKey(),
                    state.getNumericTolerance(),state.getComparisonResult(),state.getDiscrepancyReason(),
                    state.getLastReadbackTelemetryId());
        }
    }
    public record DesiredState(String stateId, Object typedValue, String valueType, String unit,
            String source, UUID commandId, Instant requestedAt, Instant acceptedForDispatchAt,
            String deliveryStatus, String ackStatus,String commandStatus,Instant publishedAt,
            Instant ackBackendReceivedAt,Instant ackDeadlineAt) {}
    public record EffectiveState(String stateId, Object value, String source,
            Object observedValue, Object intentValue,TwinStateQualityEvidence stateQuality,String verification) {}
    /**
     * Latest state for one telemetry capability belonging to this device.
     * The map key in {@code reportedState} is the capability/sensor type.
     */
    public record CapabilityState(
        String stateId,
        Double value,
        Object typedValue,
        String valueType,
        String capabilityKey,
        UUID definitionId,
        UUID bindingId,
        String resolutionSource,
        String unit,
        Instant timestamp,
        Instant lastSeen,
        String quality,
        Map<String, Object> metadata,
        TwinStateQualityEvidence stateQuality
    ) {
        private static CapabilityState from(DigitalTwin twin,TwinStateQualityEvidence evidence) {
            return new CapabilityState(
                    twin.stateKey(),
                    twin.latestValue(),
                    twin.typedValue(),
                    twin.valueType() == null ? null : twin.valueType().name(),
                    twin.capabilityKey(),
                    twin.capabilityDefinitionId(),
                    twin.bindingId(),
                    twin.resolutionSource() == null ? null : twin.resolutionSource().name(),
                    twin.latestUnit(),
                    twin.latestTimestamp(),
                    twin.lastSeen(),
                    calculateQuality(twin.lastSeen()),
                    twin.metadata(),evidence
            );
        }
    }

    public static DigitalTwinResponse from(DigitalTwin twin) {
        return from(List.of(twin));
    }

    /**
     * Builds one device-level twin without throwing away its other capability
     * states. The legacy latest* fields continue to describe the most recently
     * updated capability so existing API clients remain compatible.
     */
    public static DigitalTwinResponse from(List<DigitalTwin> twins) {
        return from(twins, List.of(), Map.of());
    }

    public static DigitalTwinResponse from(List<DigitalTwin> twins, List<DesiredTwinState> desired,
            Map<UUID, ActionHistoryRecord> actions) {
        return from(twins,desired,actions,Map.of());
    }

    public static DigitalTwinResponse from(List<DigitalTwin> twins,List<DesiredTwinState> desired,
            Map<UUID,ActionHistoryRecord> actions,Map<String,TwinStateQualityEvidence> qualities) {
        return from(twins,desired,actions,qualities,List.of());
    }

    public static DigitalTwinResponse from(List<DigitalTwin> twins,List<DesiredTwinState> desired,
            Map<UUID,ActionHistoryRecord> actions,Map<String,TwinStateQualityEvidence> qualities,
            List<CurrentTwinVerificationState> verifications) {
        return from(twins,desired,actions,qualities,verifications,List.of());
    }

    public static DigitalTwinResponse from(List<DigitalTwin> twins,List<DesiredTwinState> desired,
            Map<UUID,ActionHistoryRecord> actions,Map<String,TwinStateQualityEvidence> qualities,
            List<CurrentTwinVerificationState> verifications,List<DeviceReadbackMapping> mappings) {
        return from(twins,desired,actions,qualities,verifications,mappings,java.time.Clock.systemUTC());
    }

    public static DigitalTwinResponse from(List<DigitalTwin> twins,List<DesiredTwinState> desired,
            Map<UUID,ActionHistoryRecord> actions,Map<String,TwinStateQualityEvidence> qualities,
            List<CurrentTwinVerificationState> verifications,List<DeviceReadbackMapping> mappings,
            java.time.Clock clock) {
        if ((twins == null || twins.isEmpty()) && (desired == null || desired.isEmpty())) {
            throw new IllegalArgumentException("At least one capability state is required");
        }

        List<DigitalTwin> reported = twins == null ? List.of() : twins;
        List<DesiredTwinState> intended = desired == null ? List.of() : desired;
        DigitalTwin latest = reported.stream()
                .max(Comparator.comparing(DigitalTwin::latestTimestamp))
                .orElse(null);

        Map<String, CapabilityState> reportedState = reported.stream()
                .sorted(Comparator.comparing(DigitalTwin::latestSensorType))
                .collect(Collectors.toMap(
                        DigitalTwin::latestSensorType,
                        twin -> CapabilityState.from(twin,qualities.get(twin.stateKey())),
                        (left, right) -> right,
                        LinkedHashMap::new
                ));

        Map<String, DesiredState> desiredState = new LinkedHashMap<>();
        Map<String, EffectiveState> effectiveState = new LinkedHashMap<>();
        Map<String,CurrentTwinVerificationState> verificationById=new java.util.HashMap<>();
        for(var state:verifications)verificationById.put(state.getTargetKey()
                +(state.getSubtargetKey().isEmpty()?"":"/"+state.getSubtargetKey()),state);
        for (DigitalTwin twin : reported) {
            String id = twin.stateKey();
            effectiveState.putIfAbsent(id, new EffectiveState(id, twin.typedValue(), "REPORTED",
                    twin.typedValue(), null,qualities.get(twin.stateKey()),null));
        }
        for (DesiredTwinState state : intended) {
            String id = state.getTargetKey() + (state.getSubtargetKey().isEmpty() ? "" : "/" + state.getSubtargetKey());
            ActionHistoryRecord action = actions.get(state.getCommandId());
            CurrentTwinVerificationState currentVerification=verificationById.get(id);
            desiredState.put(id, new DesiredState(id, state.typedValue(), state.getValueType().name(),
                    state.getUnit(), state.getSource(), state.getCommandId(), state.getRequestedAt(),
                    state.getAcceptedForDispatchAt(), action == null ? "UNKNOWN" : action.getStatus().name(),
                    action == null || action.getAckStatus() == null ? null : action.getAckStatus().name(),
                    action == null ? null : CommandStatus.from(action,clock).name(),
                    action == null ? null : action.getPublishedAt(),
                    action == null ? null : action.getAckBackendReceivedAt(),
                    action == null ? null : action.getAckDeadlineAt()));
            Object observed = null;
            for (DigitalTwin twin : reported) {
                if (currentVerification!=null && currentVerification.getLastReadbackStateKey()!=null
                        && currentVerification.getLastReadbackStateKey().equals(twin.stateKey())
                        ||state.getBindingId() != null && state.getBindingId().equals(twin.bindingId())
                        || state.getLegacyCapabilityType() != null
                        && state.getLegacyCapabilityType().equals(canonicalLegacy(twin.latestSensorType()))) {
                    observed = twin.typedValue();
                    break;
                }
            }
            TwinStateQualityEvidence evidence=reported.stream().filter(t->state.getBindingId()!=null&&state.getBindingId().equals(t.bindingId())
                    ||currentVerification!=null&&currentVerification.getLastReadbackStateKey()!=null
                    &&currentVerification.getLastReadbackStateKey().equals(t.stateKey())
                    ||state.getLegacyCapabilityType()!=null&&state.getLegacyCapabilityType().equals(t.legacyCapabilityType()))
                    .findFirst().map(t->qualities.get(t.stateKey())).orElse(qualities.get(id));
            effectiveState.put(id, new EffectiveState(id, observed != null ? observed : state.typedValue(),
                    observed != null ? "REPORTED" : "DESIRED_UNVERIFIED", observed, state.typedValue(),evidence,
                    currentVerification==null?"UNVERIFIED":currentVerification.getVerificationStatus().name()));
        }

        Map<String,VerificationState> verification=new LinkedHashMap<>();
        for(var state:verifications){
            String id=state.getTargetKey()+(state.getSubtargetKey().isEmpty()?"":"/"+state.getSubtargetKey());
            verification.put(id,VerificationState.from(state,qualities.get(state.getLastReadbackStateKey())));
        }
        for(var mapping:mappings){
            if(!mapping.isEnabled())continue;
            String id="binding:"+mapping.getWriteBindingId()
                    +(mapping.getWriteSubtargetKey().isEmpty()?"":"/"+mapping.getWriteSubtargetKey());
            verification.putIfAbsent(id,new VerificationState("NO_DESIRED_STATE",null,null,null,
                    mapping.getId(),mapping.getReadBindingId(),"binding:"+mapping.getReadBindingId(),
                    null,null,null,qualities.get("binding:"+mapping.getReadBindingId()),
                    null,null,mapping.getComparatorKey(),mapping.getNumericTolerance(),null,null,null));
        }
        for(var twin:reported)verification.putIfAbsent(twin.stateKey(),new VerificationState("NOT_APPLICABLE",
                null,null,null,null,twin.bindingId(),twin.stateKey(),twin.typedValue(),twin.latestTimestamp(),
                twin.receivedAt(),qualities.get(twin.stateKey()),null,null,null,null,null,null,null));
        return new DigitalTwinResponse(
                latest != null ? latest.deviceId() : intended.getFirst().getDeviceId(),
                latest == null ? null : latest.latestTimestamp(),
                latest == null ? null : latest.latestSensorType(),
                latest == null ? null : latest.latestValue(),
                latest == null ? null : latest.latestUnit(),
                latest == null ? null : latest.lastSeen(),
                calculateQuality(latest == null ? null : latest.lastSeen()),
                latest == null ? Map.of() : latest.metadata(),
                reportedState, desiredState, effectiveState,verification
        );
    }

    private static String canonicalLegacy(String sensorType) {
        String key = sensorType.toUpperCase(java.util.Locale.ROOT);
        return key.endsWith("_SENSOR") ? key.substring(0, key.length() - 7) : key;
    }

    private static String calculateQuality(Instant lastSeen) {
        if (lastSeen == null) {
            return "UNKNOWN";
        }
        long secondsSinceLastSeen = Duration.between(lastSeen, Instant.now()).getSeconds();
        if (secondsSinceLastSeen < 30) {
            return "GOOD";
        } else if (secondsSinceLastSeen <= 120) {
            return "STALE";
        } else {
            return "OFFLINE";
        }
    }
}
