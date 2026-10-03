package com.nexus.device.template;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.*;

@Entity @Table(name = "device_templates")
public class DeviceTemplate {
    @Id private UUID id;
    @Column(name="template_key", nullable=false, unique=true, length=100) private String key;
    @Column(name="display_name", nullable=false, length=150) private String displayName;
    private String description;
    private String manufacturer;
    private String model;
    @Enumerated(EnumType.STRING) @Column(name="protocol_type", nullable=false) private ProtocolType protocolType;
    private boolean enabled;
    @Version private long version;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") private Map<String,Object> metadata;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    protected DeviceTemplate() {}
    public DeviceTemplate(UUID id, String key, String displayName, String description, String manufacturer,
            String model, ProtocolType protocolType, boolean enabled, Map<String,Object> metadata) {
        this.id=id; this.key=normalizeKey(key); this.displayName=displayName; this.description=description;
        this.manufacturer=manufacturer; this.model=model; this.protocolType=protocolType; this.enabled=enabled;
        this.metadata=metadata == null ? Map.of() : Map.copyOf(metadata); this.createdAt=Instant.now(); this.updatedAt=createdAt;
    }
    public static String normalizeKey(String value) {
        String normalized = Objects.requireNonNull(value).trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!normalized.matches("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")) throw new IllegalArgumentException("Invalid template key");
        return normalized;
    }
    public void updateMetadata(String displayName, String description, String manufacturer, String model,
            boolean enabled, Map<String,Object> metadata) {
        this.displayName=displayName; this.description=description; this.manufacturer=manufacturer; this.model=model;
        this.enabled=enabled; this.metadata=metadata == null ? Map.of() : Map.copyOf(metadata); this.updatedAt=Instant.now();
    }
    public UUID getId(){return id;} public String getKey(){return key;} public String getDisplayName(){return displayName;}
    public String getDescription(){return description;} public String getManufacturer(){return manufacturer;}
    public String getModel(){return model;} public ProtocolType getProtocolType(){return protocolType;}
    public boolean isEnabled(){return enabled;} public long getVersion(){return version;}
    public Map<String,Object> getMetadata(){return metadata;} public Instant getCreatedAt(){return createdAt;}
    public Instant getUpdatedAt(){return updatedAt;}
}
