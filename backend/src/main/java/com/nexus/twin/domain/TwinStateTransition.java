package com.nexus.twin.domain;

import com.nexus.device.capability.definition.CapabilityValueType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "twin_state_transitions")
public class TwinStateTransition {
    @Id @Column(name="transition_id") private UUID transitionId;
    @Column(name="device_id",nullable=false) private UUID deviceId;
    @Column(name="state_key",nullable=false) private String stateKey;
    @Column(name="binding_id") private UUID bindingId;
    @Column(name="legacy_capability_type") private String legacyCapabilityType;
    @Column(name="capability_definition_id") private UUID capabilityDefinitionId;
    @Column(name="capability_key") private String capabilityKey;
    @Column(name="external_identifier") private String externalIdentifier;
    @Column(name="subtarget_key",nullable=false) private String subtargetKey;
    @Enumerated(EnumType.STRING) @Column(name="transition_type",nullable=false) private TwinTransitionType transitionType;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private TwinTransitionSource source;
    @Enumerated(EnumType.STRING) @Column(name="value_type",nullable=false) private CapabilityValueType valueType;
    @Column(name="previous_numeric_value") private BigDecimal previousNumericValue;
    @Column(name="previous_boolean_value") private Boolean previousBooleanValue;
    @Column(name="previous_string_value") private String previousStringValue;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="previous_object_value",columnDefinition="jsonb") private Map<String,Object> previousObjectValue;
    @Column(name="new_numeric_value") private BigDecimal newNumericValue;
    @Column(name="new_boolean_value") private Boolean newBooleanValue;
    @Column(name="new_string_value") private String newStringValue;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="new_object_value",columnDefinition="jsonb") private Map<String,Object> newObjectValue;
    @Column(name="previous_unit") private String previousUnit;
    @Column(name="new_unit") private String newUnit;
    @Column(name="event_time") private Instant eventTime;
    @Column(name="received_at") private Instant receivedAt;
    @Column(name="requested_at") private Instant requestedAt;
    @Column(name="accepted_for_dispatch_at") private Instant acceptedForDispatchAt;
    @Column(name="transitioned_at",nullable=false) private Instant transitionedAt;
    @Column(name="correlation_id") private String correlationId;
    @Column(name="command_id") private UUID commandId;

    protected TwinStateTransition() {}

    public static TwinStateTransition reported(DigitalTwin previous, DigitalTwin next,
            String correlationId, Instant transitionedAt) {
        TwinStateTransition row=create(next.deviceId(), next.stateKey(), next.bindingId(), next.legacyCapabilityType(), "",
                TwinTransitionType.REPORTED_STATE_CHANGED, TwinTransitionSource.TELEMETRY,
                next.valueType(), previous == null ? null : previous.typedValue(), next.typedValue(),
                previous == null ? null : previous.latestUnit(), next.latestUnit(), next.latestTimestamp(),
                next.receivedAt(), transitionedAt, correlationId, null);
        row.capabilityDefinitionId=next.capabilityDefinitionId(); row.capabilityKey=next.capabilityKey();
        row.externalIdentifier=next.lastWireIdentifier();
        return row;
    }

    public static TwinStateTransition desired(DesiredTwinState previous, DesiredTwinState next,
            Instant transitionedAt) {
        return desired(previous,next,transitionedAt,null);
    }

    public static TwinStateTransition desired(DesiredTwinState previous, DesiredTwinState next,
            Instant transitionedAt,String correlationId) {
        TwinStateTransition row=create(next.getDeviceId(), next.getTargetKey(), next.getBindingId(), next.getLegacyCapabilityType(),
                next.getSubtargetKey(), TwinTransitionType.DESIRED_STATE_CHANGED,
                TwinTransitionSource.valueOf(next.getSource()), next.getValueType(),
                previous == null ? null : previous.typedValue(), next.typedValue(),
                previous == null ? null : previous.getUnit(), next.getUnit(), null, null,
                transitionedAt, correlationId, next.getCommandId());
        row.capabilityDefinitionId=next.getCapabilityDefinitionId(); row.capabilityKey=next.getCapabilityKey();
        row.requestedAt=next.getRequestedAt(); row.acceptedForDispatchAt=next.getAcceptedForDispatchAt();
        return row;
    }

    private static TwinStateTransition create(UUID deviceId, String stateKey, UUID bindingId,
            String legacyType, String subtarget, TwinTransitionType type, TwinTransitionSource source,
            CapabilityValueType valueType, Object previous, Object next, String previousUnit, String nextUnit,
            Instant eventTime, Instant receivedAt, Instant transitionedAt, String correlationId, UUID commandId) {
        TwinStateTransition row = new TwinStateTransition();
        row.transitionId=UUID.randomUUID(); row.deviceId=deviceId; row.stateKey=stateKey;
        row.bindingId=bindingId; row.legacyCapabilityType=legacyType; row.subtargetKey=subtarget;
        row.transitionType=type; row.source=source; row.valueType=valueType;
        row.assignPrevious(previous); row.assignNew(next); row.previousUnit=previousUnit; row.newUnit=nextUnit;
        row.eventTime=eventTime; row.receivedAt=receivedAt; row.transitionedAt=transitionedAt;
        row.correlationId=correlationId; row.commandId=commandId;
        return row;
    }

    @SuppressWarnings("unchecked") private void assignPrevious(Object value) {
        if (value == null) return;
        switch(valueType){case NUMBER->previousNumericValue=new BigDecimal(value.toString());case BOOLEAN->previousBooleanValue=(Boolean)value;case STRING,ENUM->previousStringValue=value.toString();case OBJECT->previousObjectValue=(Map<String,Object>)value;}
    }
    @SuppressWarnings("unchecked") private void assignNew(Object value) {
        if (value == null) return;
        switch(valueType){case NUMBER->newNumericValue=new BigDecimal(value.toString());case BOOLEAN->newBooleanValue=(Boolean)value;case STRING,ENUM->newStringValue=value.toString();case OBJECT->newObjectValue=(Map<String,Object>)value;}
    }
    public UUID getTransitionId(){return transitionId;} public UUID getDeviceId(){return deviceId;}
    public String getStateKey(){return stateKey;} public UUID getBindingId(){return bindingId;}
    public String getLegacyCapabilityType(){return legacyCapabilityType;} public String getSubtargetKey(){return subtargetKey;}
    public UUID getCapabilityDefinitionId(){return capabilityDefinitionId;} public String getCapabilityKey(){return capabilityKey;}
    public String getExternalIdentifier(){return externalIdentifier;}
    public TwinTransitionType getTransitionType(){return transitionType;} public TwinTransitionSource getSource(){return source;}
    public CapabilityValueType getValueType(){return valueType;} public String getPreviousUnit(){return previousUnit;}
    public String getNewUnit(){return newUnit;} public Instant getEventTime(){return eventTime;}
    public Instant getReceivedAt(){return receivedAt;} public Instant getTransitionedAt(){return transitionedAt;}
    public Instant getRequestedAt(){return requestedAt;} public Instant getAcceptedForDispatchAt(){return acceptedForDispatchAt;}
    public String getCorrelationId(){return correlationId;} public UUID getCommandId(){return commandId;}
    public Object previousValue(){return value(previousNumericValue,previousBooleanValue,previousStringValue,previousObjectValue);}
    public Object newValue(){return value(newNumericValue,newBooleanValue,newStringValue,newObjectValue);}
    private Object value(BigDecimal number,Boolean bool,String string,Map<String,Object> object){return switch(valueType){case NUMBER->number;case BOOLEAN->bool;case STRING,ENUM->string;case OBJECT->object;};}
}
