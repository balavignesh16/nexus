package com.nexus.twin.api.dto;

import com.nexus.twin.domain.TwinStateTransition;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TwinHistoryResponse(List<Item> items,String nextCursor) {
    public record Item(UUID transitionId,UUID deviceId,String stateId,UUID bindingId,String legacyKey,
            UUID capabilityDefinitionId,String capabilityKey,String externalIdentifier,String subtargetKey,String transitionType,String source,String valueType,Object previousValue,
            Object newValue,String previousUnit,String newUnit,Instant eventTime,Instant receivedAt,
            Instant requestedAt,Instant acceptedForDispatchAt,Instant transitionedAt,String correlationId,UUID commandId,
            String previousVerificationStatus,String newVerificationStatus,UUID verificationCommandId,
            UUID verificationMappingId,UUID readbackTelemetryId,String readbackStateKey,UUID readbackBindingId,
            Instant readbackEventTime,Instant readbackReceivedAt,String readbackEvidence,String readbackAttribution,
            String comparatorKey,String comparisonResult,java.math.BigDecimal numericTolerance,String verificationReason) {
        public static Item from(TwinStateTransition t){return new Item(t.getTransitionId(),t.getDeviceId(),t.getStateKey(),
                t.getBindingId(),t.getLegacyCapabilityType(),t.getCapabilityDefinitionId(),t.getCapabilityKey(),
                t.getExternalIdentifier(),t.getSubtargetKey(),t.getTransitionType().name(),
                t.getSource().name(),t.getValueType().name(),t.previousValue(),t.newValue(),t.getPreviousUnit(),
                t.getNewUnit(),t.getEventTime(),t.getReceivedAt(),t.getRequestedAt(),t.getAcceptedForDispatchAt(),
                t.getTransitionedAt(),t.getCorrelationId(),t.getCommandId(),
                t.getPreviousVerificationStatus(),t.getNewVerificationStatus(),t.getVerificationCommandId(),
                t.getVerificationMappingId(),t.getReadbackTelemetryId(),t.getReadbackStateKey(),t.getReadbackBindingId(),
                t.getReadbackEventTime(),t.getReadbackReceivedAt(),t.getReadbackEvidence(),t.getReadbackAttribution(),
                t.getComparatorKey(),t.getComparisonResult(),t.getNumericTolerance(),t.getVerificationReason());}
    }
}
