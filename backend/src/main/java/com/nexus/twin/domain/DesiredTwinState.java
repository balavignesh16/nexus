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
@Table(name = "current_desired_twin_state")
@IdClass(DesiredTwinStateId.class)
public class DesiredTwinState {
    @Id @Column(name = "device_id") private UUID deviceId;
    @Id @Column(name = "target_key") private String targetKey;
    @Id @Column(name = "subtarget_key") private String subtargetKey;
    @Column(name = "binding_id") private UUID bindingId;
    @Column(name = "legacy_capability_type") private String legacyCapabilityType;
    @Column(name = "capability_definition_id") private UUID capabilityDefinitionId;
    @Column(name = "capability_key") private String capabilityKey;
    @Enumerated(EnumType.STRING) @Column(name = "value_type") private CapabilityValueType valueType;
    @Column(name = "numeric_value") private BigDecimal numericValue;
    @Column(name = "boolean_value") private Boolean booleanValue;
    @Column(name = "string_value") private String stringValue;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "object_value", columnDefinition = "jsonb") private Map<String,Object> objectValue;
    private String unit;
    private String source;
    @Column(name = "command_id") private UUID commandId;
    @Column(name = "requested_at") private Instant requestedAt;
    @Column(name = "accepted_for_dispatch_at") private Instant acceptedForDispatchAt;

    protected DesiredTwinState() {}

    @SuppressWarnings("unchecked")
    public DesiredTwinState(UUID deviceId, String targetKey, UUID bindingId, String legacyCapabilityType,
            String subtargetKey, UUID definitionId, String capabilityKey, CapabilityValueType valueType,
            Object value, String unit, String source, UUID commandId, Instant requestedAt, Instant acceptedAt) {
        this.deviceId=deviceId; this.targetKey=targetKey; this.bindingId=bindingId;
        this.legacyCapabilityType=legacyCapabilityType; this.subtargetKey=subtargetKey;
        this.capabilityDefinitionId=definitionId; this.capabilityKey=capabilityKey;
        this.valueType=valueType; this.unit=unit; this.source=source;
        this.commandId=commandId; this.requestedAt=requestedAt; this.acceptedForDispatchAt=acceptedAt;
        switch (valueType) {
            case NUMBER -> numericValue = new BigDecimal(value.toString());
            case BOOLEAN -> booleanValue = (Boolean) value;
            case STRING, ENUM -> stringValue = (String) value;
            case OBJECT -> objectValue = (Map<String,Object>) value;
        }
    }
    public UUID getDeviceId(){return deviceId;}
    public String getTargetKey(){return targetKey;}
    public String getSubtargetKey(){return subtargetKey;}
    public UUID getBindingId(){return bindingId;}
    public String getLegacyCapabilityType(){return legacyCapabilityType;}
    public UUID getCapabilityDefinitionId(){return capabilityDefinitionId;}
    public String getCapabilityKey(){return capabilityKey;}
    public CapabilityValueType getValueType(){return valueType;}
    public String getUnit(){return unit;}
    public String getSource(){return source;}
    public UUID getCommandId(){return commandId;}
    public Instant getRequestedAt(){return requestedAt;}
    public Instant getAcceptedForDispatchAt(){return acceptedForDispatchAt;}
    public Object typedValue(){return switch(valueType){
        case NUMBER -> numericValue; case BOOLEAN -> booleanValue;
        case STRING, ENUM -> stringValue; case OBJECT -> objectValue;
    };}
}
