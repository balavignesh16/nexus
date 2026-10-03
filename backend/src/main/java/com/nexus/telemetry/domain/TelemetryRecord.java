package com.nexus.telemetry.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.Map;
import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.device.binding.runtime.CapabilityResolutionSource;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "telemetry_history")
@IdClass(TelemetryRecordId.class)
public class TelemetryRecord {
    @Id
    @Column(name = "id")
    private UUID id;
    
    @Id
    @Column(name = "timestamp")
    private Instant timestamp;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "sensor_type", nullable = false)
    private String sensorType;

    @Column(name = "\"value\"") private Double value;

    @Column(name = "unit")
    private String unit;

    @Column(name="capability_definition_id") private UUID capabilityDefinitionId;
    @Column(name="binding_id") private UUID bindingId;
    @Column(name="capability_key") private String capabilityKey;
    @Enumerated(EnumType.STRING) @Column(name="value_type") private CapabilityValueType valueType;
    @Column(name="numeric_value") private BigDecimal numericValue;
    @Column(name="boolean_value") private Boolean booleanValue;
    @Column(name="string_value") private String stringValue;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="object_value",columnDefinition="jsonb") private Map<String,Object> objectValue;
    @Enumerated(EnumType.STRING) @Column(name="resolution_source") private CapabilityResolutionSource resolutionSource;

    protected TelemetryRecord() {}

    public TelemetryRecord(UUID id, UUID deviceId, Instant timestamp, String sensorType, double value, String unit) {
        this.id = id;
        this.deviceId = deviceId;
        this.timestamp = timestamp;
        this.sensorType = sensorType;
        this.value = value;
        this.unit = unit;
    }
    public TelemetryRecord(UUID id,UUID deviceId,Instant timestamp,String sensorType,Object typedValue,String unit,
            UUID definitionId,UUID bindingId,String capabilityKey,CapabilityValueType valueType,CapabilityResolutionSource source){
        this.id=id;this.deviceId=deviceId;this.timestamp=timestamp;this.sensorType=sensorType;this.unit=unit;
        this.capabilityDefinitionId=definitionId;this.bindingId=bindingId;this.capabilityKey=capabilityKey;this.valueType=valueType;this.resolutionSource=source;
        switch(valueType){case NUMBER -> {numericValue=new BigDecimal(typedValue.toString());value=numericValue.doubleValue();}
            case BOOLEAN -> booleanValue=(Boolean)typedValue; case STRING,ENUM -> stringValue=(String)typedValue;
            case OBJECT -> objectValue=(Map<String,Object>)typedValue;}
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getSensorType() {
        return sensorType;
    }

    public Double getValue() {
        return value;
    }

    public String getUnit() {
        return unit;
    }
    public UUID getCapabilityDefinitionId(){return capabilityDefinitionId;} public UUID getBindingId(){return bindingId;}
    public String getCapabilityKey(){return capabilityKey;} public CapabilityValueType getValueType(){return valueType;}
    public Object getTypedValue(){return switch(valueType==null?CapabilityValueType.NUMBER:valueType){case NUMBER->numericValue!=null?numericValue:value;case BOOLEAN->booleanValue;case STRING,ENUM->stringValue;case OBJECT->objectValue;};}
    public CapabilityResolutionSource getResolutionSource(){return resolutionSource;}
}
