package com.nexus.twin.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity @Table(name="template_readback_mappings")
public class TemplateReadbackMapping {
    @Id private UUID id;
    @Column(name="template_id") private UUID templateId;
    @Column(name="write_template_capability_id") private UUID writeTemplateCapabilityId;
    @Column(name="write_subtarget_key") private String writeSubtargetKey;
    @Column(name="read_template_capability_id") private UUID readTemplateCapabilityId;
    @Column(name="selector_key") private String selectorKey;
    @Column(name="selector_path") private String selectorPath;
    @Column(name="comparator_key") private String comparatorKey;
    @Column(name="numeric_tolerance") private BigDecimal numericTolerance;
    @Column(name="qualification_mode") private String qualificationMode;
    @Column(name="verification_timeout_seconds") private int verificationTimeoutSeconds;
    protected TemplateReadbackMapping() {}
    public TemplateReadbackMapping(UUID id,UUID templateId,UUID writeId,String subtarget,UUID readId,
            String selector,String path,String comparator,BigDecimal tolerance,String qualifier,int timeout) {
        this.id=id;this.templateId=templateId;this.writeTemplateCapabilityId=writeId;this.writeSubtargetKey=subtarget;
        this.readTemplateCapabilityId=readId;this.selectorKey=selector;this.selectorPath=path;
        this.comparatorKey=comparator;this.numericTolerance=tolerance;this.qualificationMode=qualifier;
        this.verificationTimeoutSeconds=timeout;
    }
    public UUID getId(){return id;} public UUID getTemplateId(){return templateId;}
    public UUID getWriteTemplateCapabilityId(){return writeTemplateCapabilityId;}
    public String getWriteSubtargetKey(){return writeSubtargetKey;}
    public UUID getReadTemplateCapabilityId(){return readTemplateCapabilityId;}
    public String getSelectorKey(){return selectorKey;} public String getSelectorPath(){return selectorPath;}
    public String getComparatorKey(){return comparatorKey;} public BigDecimal getNumericTolerance(){return numericTolerance;}
    public String getQualificationMode(){return qualificationMode;}
    public int getVerificationTimeoutSeconds(){return verificationTimeoutSeconds;}
}
