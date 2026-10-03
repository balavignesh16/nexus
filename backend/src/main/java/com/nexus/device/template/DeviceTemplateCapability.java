package com.nexus.device.template;

import com.nexus.device.capability.definition.CapabilityDefinition;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.*;

@Entity @Table(name="device_template_capabilities", uniqueConstraints=@UniqueConstraint(columnNames={"template_id","capability_definition_id"}))
public class DeviceTemplateCapability {
    @Id private UUID id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="template_id") private DeviceTemplate template;
    @ManyToOne(fetch=FetchType.EAGER, optional=false) @JoinColumn(name="capability_definition_id") private CapabilityDefinition definition;
    private boolean required;
    @Column(name="default_unit") private String defaultUnit;
    @Column(name="display_order") private int displayOrder;
    @Column(name="freshness_threshold_seconds") private Integer freshnessThresholdSeconds;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") private Map<String,Object> configuration;
    protected DeviceTemplateCapability() {}
    public DeviceTemplateCapability(UUID id, DeviceTemplate template, CapabilityDefinition definition, boolean required,
            String defaultUnit, int displayOrder, Integer freshness, Map<String,Object> configuration) {
        this.id=id; this.template=template; this.definition=definition; this.required=required; this.defaultUnit=defaultUnit;
        this.displayOrder=displayOrder; this.freshnessThresholdSeconds=freshness;
        this.configuration=configuration == null ? Map.of() : Map.copyOf(configuration);
        validate();
    }
    private void validate() {
        if (defaultUnit != null && !definition.getAllowedUnits().isEmpty() && !definition.getAllowedUnits().contains(defaultUnit))
            throw new IllegalArgumentException("Default unit is not allowed by capability definition");
        if (freshnessThresholdSeconds != null && freshnessThresholdSeconds <= 0) throw new IllegalArgumentException("Freshness must be positive");
    }
    public void update(boolean required,String unit,int order,Integer freshness,Map<String,Object> config){this.required=required;this.defaultUnit=unit;this.displayOrder=order;this.freshnessThresholdSeconds=freshness;this.configuration=config==null?Map.of():Map.copyOf(config);validate();}
    public UUID getId(){return id;} public DeviceTemplate getTemplate(){return template;} public CapabilityDefinition getDefinition(){return definition;}
    public boolean isRequired(){return required;} public String getDefaultUnit(){return defaultUnit;} public int getDisplayOrder(){return displayOrder;}
    public Integer getFreshnessThresholdSeconds(){return freshnessThresholdSeconds;} public Map<String,Object> getConfiguration(){return configuration;}
}
