package com.nexus.device.binding;

import com.nexus.device.capability.definition.CapabilityDefinition;
import com.nexus.device.template.DeviceTemplateCapability;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.*;

@Entity @Table(name="device_capability_bindings", uniqueConstraints=@UniqueConstraint(columnNames={"device_id","capability_definition_id"}))
public class DeviceCapabilityBinding {
    @Id private UUID id;
    @Column(name="device_id",nullable=false) private UUID deviceId;
    @ManyToOne(fetch=FetchType.EAGER,optional=false) @JoinColumn(name="capability_definition_id") private CapabilityDefinition definition;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="template_capability_id") private DeviceTemplateCapability templateCapability;
    private boolean enabled;
    private String unit;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") private Map<String,Object> configuration;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="external_identifiers",columnDefinition="jsonb") private Map<String,Object> externalIdentifiers;
    @Column(name="created_at") private Instant createdAt;
    @Column(name="updated_at") private Instant updatedAt;
    protected DeviceCapabilityBinding() {}
    public DeviceCapabilityBinding(UUID id, UUID deviceId, CapabilityDefinition definition,
            DeviceTemplateCapability templateCapability, boolean enabled, String unit,
            Map<String,Object> configuration, Map<String,Object> externalIdentifiers, Instant now) {
        this.id=id; this.deviceId=deviceId; this.definition=definition; this.templateCapability=templateCapability;
        this.enabled=enabled; this.unit=unit;
        this.configuration=Map.copyOf(configuration); this.externalIdentifiers=Map.copyOf(externalIdentifiers);
        this.createdAt=now; this.updatedAt=now;
    }
    public void update(boolean enabled, String unit, Map<String,Object> configuration,
            Map<String,Object> externalIdentifiers, Instant now) {
        this.enabled=enabled; this.unit=unit;
        this.configuration=Map.copyOf(configuration); this.externalIdentifiers=Map.copyOf(externalIdentifiers);
        this.updatedAt=now;
    }
    public UUID getId(){return id;} public UUID getDeviceId(){return deviceId;} public CapabilityDefinition getDefinition(){return definition;}
    public DeviceTemplateCapability getTemplateCapability(){return templateCapability;} public boolean isEnabled(){return enabled;}
    public String getUnit(){return unit;} public Map<String,Object> getConfiguration(){return configuration;}
    public Map<String,Object> getExternalIdentifiers(){return externalIdentifiers;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
