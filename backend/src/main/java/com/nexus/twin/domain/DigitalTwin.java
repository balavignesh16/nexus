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
    @Column(name = "latest_sensor_type", nullable = false)
    private String latestSensorType;

    @Id
    @Column(name = "state_key", nullable = false)
    private String stateKey;

    @Column(name = "legacy_capability_type")
    private String legacyCapabilityType;

    @Column(name = "last_wire_identifier")
    private String lastWireIdentifier;

    @Column(name = "latest_value")
    private Double latestValue;

    @Column(name = "latest_unit")
    private String latestUnit;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    @Column(name = "received_at")
    private Instant receivedAt;

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
        this.legacyCapabilityType = canonicalLegacy(latestSensorType);
        this.stateKey = "legacy:" + legacyCapabilityType;
        this.lastWireIdentifier = latestSensorType;
        this.latestValue = latestValue;
        this.latestUnit = latestUnit;
        this.lastSeen = lastSeen;
        this.metadata = metadata;
    }
    @SuppressWarnings("unchecked")
    public DigitalTwin(UUID deviceId,Instant timestamp,String sensorType,Object typedValue,String unit,Instant lastSeen,Map<String,Object> metadata,
      UUID definitionId,UUID bindingId,String capabilityKey,CapabilityValueType valueType,CapabilityResolutionSource source){
      this(deviceId,timestamp,sensorType,typedValue,unit,lastSeen,lastSeen,metadata,definitionId,bindingId,capabilityKey,valueType,source);
    }
    @SuppressWarnings("unchecked")
    public DigitalTwin(UUID deviceId,Instant timestamp,String sensorType,Object typedValue,String unit,Instant lastSeen,Instant receivedAt,Map<String,Object> metadata,
      UUID definitionId,UUID bindingId,String capabilityKey,CapabilityValueType valueType,CapabilityResolutionSource source){
      this(deviceId,timestamp,sensorType,sensorType,typedValue,unit,lastSeen,receivedAt,metadata,definitionId,bindingId,capabilityKey,valueType,source);
    }
    @SuppressWarnings("unchecked")
    public DigitalTwin(UUID deviceId,Instant timestamp,String sensorType,String wireIdentifier,Object typedValue,String unit,Instant lastSeen,Instant receivedAt,Map<String,Object> metadata,
      UUID definitionId,UUID bindingId,String capabilityKey,CapabilityValueType valueType,CapabilityResolutionSource source){
      this.deviceId=deviceId;this.latestTimestamp=timestamp;this.latestSensorType=sensorType;this.latestUnit=unit;this.lastSeen=lastSeen;this.metadata=metadata;
      this.capabilityDefinitionId=definitionId;this.bindingId=bindingId;this.capabilityKey=capabilityKey;this.valueType=valueType;this.resolutionSource=source;
      this.receivedAt=receivedAt; this.lastWireIdentifier=wireIdentifier;
      this.legacyCapabilityType=bindingId==null?canonicalLegacy(sensorType):null;
      this.stateKey=bindingId!=null?"binding:"+bindingId:"legacy:"+legacyCapabilityType;
      switch(valueType){case NUMBER->{numericValue=new BigDecimal(typedValue.toString());latestValue=numericValue.doubleValue();}case BOOLEAN->booleanValue=(Boolean)typedValue;case STRING,ENUM->stringValue=(String)typedValue;case OBJECT->objectValue=(Map<String,Object>)typedValue;}
    }

    private static String canonicalLegacy(String sensorType) {
        String key=sensorType.toUpperCase(java.util.Locale.ROOT);
        return key.endsWith("_SENSOR")?key.substring(0,key.length()-7):key;
    }

    public UUID deviceId() { return deviceId; }
    public Instant latestTimestamp() { return latestTimestamp; }
    public String latestSensorType() { return latestSensorType; }
    public Double latestValue() { return latestValue; }
    public String latestUnit() { return latestUnit; }
    public Instant lastSeen() { return lastSeen; }
    public Instant receivedAt() { return receivedAt; }
    public String stateKey(){return stateKey;} public String legacyCapabilityType(){return legacyCapabilityType;}
    public String lastWireIdentifier(){return lastWireIdentifier;}
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
    public Instant getReceivedAt(){return receivedAt;} public String getStateKey(){return stateKey;}
    public String getLegacyCapabilityType(){return legacyCapabilityType;} public String getLastWireIdentifier(){return lastWireIdentifier;}
    public Map<String, Object> getMetadata() { return metadata; }
    public UUID getCapabilityDefinitionId(){return capabilityDefinitionId;} public UUID getBindingId(){return bindingId;}
    public String getCapabilityKey(){return capabilityKey;} public CapabilityValueType getValueType(){return valueType;}
    public BigDecimal getNumericValue(){return numericValue;} public Boolean getBooleanValue(){return booleanValue;}
    public String getStringValue(){return stringValue;} public Map<String,Object> getObjectValue(){return objectValue;}
    public CapabilityResolutionSource getResolutionSource(){return resolutionSource;}
}
