package com.nexus.twin.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.device.binding.runtime.CapabilityResolutionSource;

@Entity
@Table(name = "digital_twins")
@IdClass(DigitalTwinId.class)
public class DigitalTwin {

    @Id
    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "latest_timestamp", nullable = false)
    private Instant latestTimestamp;

    // Phase 11: part of the composite identity (deviceId, latestSensorType)
    // - a device reporting multiple real sensor types now retains one
    // persisted row per sensorType, instead of the newest overwriting all
    // others. Always populated (see V13 migration for the non-null proof).
    @Id
    @Column(name = "latest_sensor_type", nullable = false)
    private String latestSensorType;

    @Column(name = "latest_value")
    private Double latestValue;

    @Column(name = "latest_unit")
    private String latestUnit;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;
    @Column(name="capability_definition_id") private UUID capabilityDefinitionId;
    @Column(name="binding_id") private UUID bindingId;
    @Column(name="capability_key") private String capabilityKey;
    @Enumerated(EnumType.STRING) @Column(name="value_type") private CapabilityValueType valueType;
    @Column(name="numeric_value") private BigDecimal numericValue;
    @Column(name="boolean_value") private Boolean booleanValue;
    @Column(name="string_value") private String stringValue;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="object_value",columnDefinition="jsonb") private Map<String,Object> objectValue;
    @Enumerated(EnumType.STRING) @Column(name="resolution_source") private CapabilityResolutionSource resolutionSource;

    protected DigitalTwin() {}

    public DigitalTwin(UUID deviceId, Instant latestTimestamp, String latestSensorType, Double latestValue, String latestUnit, Instant lastSeen, Map<String, Object> metadata) {
        this.deviceId = deviceId;
        this.latestTimestamp = latestTimestamp;
        this.latestSensorType = latestSensorType;
        this.latestValue = latestValue;
        this.latestUnit = latestUnit;
        this.lastSeen = lastSeen;
        this.metadata = metadata;
    }
    @SuppressWarnings("unchecked")
    public DigitalTwin(UUID deviceId,Instant timestamp,String sensorType,Object typedValue,String unit,Instant lastSeen,Map<String,Object> metadata,
      UUID definitionId,UUID bindingId,String capabilityKey,CapabilityValueType valueType,CapabilityResolutionSource source){
      this.deviceId=deviceId;this.latestTimestamp=timestamp;this.latestSensorType=sensorType;this.latestUnit=unit;this.lastSeen=lastSeen;this.metadata=metadata;
      this.capabilityDefinitionId=definitionId;this.bindingId=bindingId;this.capabilityKey=capabilityKey;this.valueType=valueType;this.resolutionSource=source;
      switch(valueType){case NUMBER->{numericValue=new BigDecimal(typedValue.toString());latestValue=numericValue.doubleValue();}case BOOLEAN->booleanValue=(Boolean)typedValue;case STRING,ENUM->stringValue=(String)typedValue;case OBJECT->objectValue=(Map<String,Object>)typedValue;}
    }

    public UUID deviceId() { return deviceId; }
    public Instant latestTimestamp() { return latestTimestamp; }
    public String latestSensorType() { return latestSensorType; }
    public Double latestValue() { return latestValue; }
    public String latestUnit() { return latestUnit; }
    public Instant lastSeen() { return lastSeen; }
    public Map<String, Object> metadata() { return metadata; }
    public UUID capabilityDefinitionId(){return capabilityDefinitionId;} public UUID bindingId(){return bindingId;}
    public String capabilityKey(){return capabilityKey;} public CapabilityValueType valueType(){return valueType;}
    public Object typedValue(){return switch(valueType==null?CapabilityValueType.NUMBER:valueType){case NUMBER->numericValue!=null?numericValue:latestValue;case BOOLEAN->booleanValue;case STRING,ENUM->stringValue;case OBJECT->objectValue;};}
    public CapabilityResolutionSource resolutionSource(){return resolutionSource;}

    public UUID getDeviceId() { return deviceId; }
    public Instant getLatestTimestamp() { return latestTimestamp; }
    public String getLatestSensorType() { return latestSensorType; }
    public Double getLatestValue() { return latestValue; }
    public String getLatestUnit() { return latestUnit; }
    public Instant getLastSeen() { return lastSeen; }
    public Map<String, Object> getMetadata() { return metadata; }
    public UUID getCapabilityDefinitionId(){return capabilityDefinitionId;} public UUID getBindingId(){return bindingId;}
    public String getCapabilityKey(){return capabilityKey;} public CapabilityValueType getValueType(){return valueType;}
    public BigDecimal getNumericValue(){return numericValue;} public Boolean getBooleanValue(){return booleanValue;}
    public String getStringValue(){return stringValue;} public Map<String,Object> getObjectValue(){return objectValue;}
    public CapabilityResolutionSource getResolutionSource(){return resolutionSource;}
}
