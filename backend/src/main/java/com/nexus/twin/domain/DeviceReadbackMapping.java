package com.nexus.twin.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity @Table(name="device_readback_mappings")
public class DeviceReadbackMapping {
    @Id private UUID id;
    @Column(name="device_id") private UUID deviceId;
    @Column(name="write_binding_id") private UUID writeBindingId;
    @Column(name="write_subtarget_key") private String writeSubtargetKey;
    @Column(name="read_binding_id") private UUID readBindingId;
    @Column(name="selector_key") private String selectorKey;
    @Column(name="selector_path") private String selectorPath;
    @Column(name="comparator_key") private String comparatorKey;
    @Column(name="numeric_tolerance") private BigDecimal numericTolerance;
    @Column(name="qualification_mode") private String qualificationMode;
    @Column(name="verification_timeout_seconds") private int verificationTimeoutSeconds;
    private boolean enabled;
    @Column(name="template_mapping_id") private UUID templateMappingId;
    protected DeviceReadbackMapping() {}
    public DeviceReadbackMapping(UUID id,UUID deviceId,UUID writeBindingId,String subtarget,UUID readBindingId,
            String selector,String path,String comparator,BigDecimal tolerance,String qualifier,int timeout,UUID templateMappingId) {
        this.id=id;this.deviceId=deviceId;this.writeBindingId=writeBindingId;this.writeSubtargetKey=subtarget;
        this.readBindingId=readBindingId;this.selectorKey=selector;this.selectorPath=path;
        this.comparatorKey=comparator;this.numericTolerance=tolerance;this.qualificationMode=qualifier;
        this.verificationTimeoutSeconds=timeout;this.templateMappingId=templateMappingId;this.enabled=true;
    }
    public void disable(){enabled=false;}
    public UUID getId(){return id;} public UUID getDeviceId(){return deviceId;}
    public UUID getWriteBindingId(){return writeBindingId;} public String getWriteSubtargetKey(){return writeSubtargetKey;}
    public UUID getReadBindingId(){return readBindingId;} public String getSelectorKey(){return selectorKey;}
    public String getSelectorPath(){return selectorPath;} public String getComparatorKey(){return comparatorKey;}
    public BigDecimal getNumericTolerance(){return numericTolerance;} public String getQualificationMode(){return qualificationMode;}
    public int getVerificationTimeoutSeconds(){return verificationTimeoutSeconds;}
    public UUID getTemplateMappingId(){return templateMappingId;} public boolean isEnabled(){return enabled;}
}
